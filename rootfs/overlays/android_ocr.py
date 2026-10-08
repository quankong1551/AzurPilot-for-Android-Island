"""将白名单 ONNX OCR 会话的张量推理交给 APK，失败时惰性回退原会话。

仅在宿主注入地址和口令时安装。按模型内容哈希匹配，热更后的新模型不会误用旧权重；
AP/RapidOCR 的图像预处理、语言字典和 CTC 解码保持原有语义。

Delegates allowlisted ONNX OCR tensor inference to the APK with lazy original-session fallback.
Installation requires a host-injected address and token. Content hashes prevent stale weights
after hot updates. AP/RapidOCR retain their preprocessing, dictionaries, and CTC decoding.
"""

import hashlib
import json
import logging
import math
import os
import socket
import threading
import time
from pathlib import Path
from types import SimpleNamespace

MAX_HEADER = 1024 * 1024
MAX_PAYLOAD = 64 * 1024 * 1024
LOGGER = logging.getLogger("android_ocr")


class AndroidSession:
    """兼容 RapidOCR 使用的 ONNX 会话方法；每个会话串行访问连接。

    Implements the ONNX session methods used by RapidOCR, serializing each connection.
    """

    def __init__(self, original, args, kwargs, model_hash, address, token):
        self._original = original
        self._args, self._kwargs = args, kwargs
        self._hash, self._address, self._token = model_hash, address, token
        self._cpu = None
        self._socket = self._reader = None
        self._lock = threading.Lock()
        self._metadata = self._request("describe")[0]["model"]
        self._failed = False
        self._last_backend = "uninitialized"
        self._host_runs = 0

    def _connect(self):
        if self._socket is None:
            host, port = self._address.rsplit(":", 1)
            self._socket = socket.create_connection((host, int(port)), timeout=3)
            self._socket.settimeout(90)
            self._reader = self._socket.makefile("rb")

    def _close(self):
        reader = self.__dict__.get("_reader")
        connection = self.__dict__.get("_socket")
        if reader is not None:
            reader.close()
        if connection is not None:
            connection.close()
        self._socket = self._reader = None

    def _read_exact(self, count):
        if not 0 <= count <= MAX_PAYLOAD:
            raise ValueError("OCR response payload is too large")
        output = bytearray(count)
        offset = 0
        while offset < count:
            chunk = self._reader.read(count - offset)
            if not chunk:
                raise ConnectionError("Truncated OCR tensor")
            output[offset:offset + len(chunk)] = chunk
            offset += len(chunk)
        return output

    def _request(self, method, payload=b"", **fields):
        # 每次请求释放连接槽，防止多个 OCR 实例的闲置会话占满宿主；网络中断重试一次。
        for attempt in range(2):
            try:
                return self._request_once(method, payload, **fields)
            except (OSError, ConnectionError):
                if attempt:
                    raise
            finally:
                self._close()

    def _request_once(self, method, payload=b"", **fields):
        self._connect()
        request = {"method": method, "model_sha256": self._hash,
                   "token": self._token, **fields}
        header = json.dumps(request, separators=(",", ":")).encode() + b"\n"
        if len(header) > MAX_HEADER or len(payload) > MAX_PAYLOAD:
            raise ValueError("OCR request exceeds the protocol limit")
        self._socket.sendall(header)
        if payload:
            self._socket.sendall(payload)
        line = self._reader.readline(MAX_HEADER + 1)
        if not line:
            raise ConnectionError("OCR server closed the connection")
        if not line.endswith(b"\n") or len(line) > MAX_HEADER:
            raise ValueError("Invalid OCR response header")
        reply = json.loads(line)
        if not reply.get("ok"):
            raise RuntimeError(reply.get("error", "Android OCR failed"))
        data = self._read_exact(reply.get("length", 0))
        return reply, data

    def _fallback(self):
        if self._cpu is None:
            self._cpu = self._original(*self._args, **self._kwargs)
        return self._cpu

    def get_inputs(self):
        """返回原 ONNX 输入元数据。 / Returns original ONNX input metadata."""
        return [SimpleNamespace(**item) for item in self._metadata["inputs"]]

    def get_outputs(self):
        """返回原 ONNX 输出元数据。 / Returns original ONNX output metadata."""
        return [SimpleNamespace(**item) for item in self._metadata["outputs"]]

    def get_modelmeta(self):
        """保留 RapidOCR 从模型读取字典的能力。 / Preserves RapidOCR's model dictionary lookup."""
        return SimpleNamespace(custom_metadata_map=self._metadata.get("custom_metadata_map", {}))

    def get_providers(self):
        """返回 ORT 兼容名称；实际后端由宿主状态接口给出。

        Returns an ORT-compatible name; the host status endpoint reports the actual backend.
        """
        return ["CPUExecutionProvider"]

    def run(self, output_names, input_feed, run_options=None):
        """推理单个 FP32 输入，返回原 ONNX 顺序的数组；错误后本会话回退 CPU。

        Runs one FP32 input and returns arrays in ONNX order; errors switch this session to CPU.
        """
        import numpy as np

        with self._lock:
            try:
                if self._failed or run_options is not None:
                    return self._fallback().run(output_names, input_feed, run_options)
                names = [item["name"] for item in self._metadata["inputs"]]
                if list(input_feed) != names or len(names) != 1:
                    raise ValueError("Android OCR expects one named input")
                values = np.asarray(input_feed[names[0]])
                if values.dtype != np.float32 or not np.isfinite(values).all():
                    raise ValueError("Android OCR expects finite float32 tensors")
                values = np.ascontiguousarray(values, dtype="<f4")
                reply, payload = self._request("run", values.tobytes(), shape=list(values.shape),
                                               input_name=names[0], length=values.nbytes,
                                               source="diagnostic" if os.environ.get("AZURPILOT_OCR_TEST") == "1" else "ap")
                outputs = {}
                offset = 0
                for item in reply["outputs"]:
                    shape = tuple(item["shape"])
                    if not shape or len(shape) > 4 or any(type(d) is not int or d <= 0 for d in shape):
                        raise ValueError("Invalid OCR output shape")
                    size = math.prod(shape) * 4
                    if not 0 < size <= len(payload) - offset or item["name"] in outputs:
                        raise ValueError("Invalid OCR output shape")
                    result = np.frombuffer(payload, dtype="<f4", count=size // 4, offset=offset).reshape(shape)
                    if not np.isfinite(result).all():
                        raise ValueError("Non-finite Android OCR output")
                    outputs[item["name"]] = result
                    offset += size
                if offset != len(payload):
                    raise ValueError("Trailing OCR tensor bytes")
                selected = output_names or [item["name"] for item in self._metadata["outputs"]]
                self._last_backend = reply.get("backend", "unknown")
                self._host_runs += 1
                return [outputs[name] for name in selected]
            except Exception as error:
                self._close()
                self._failed = True
                self._last_backend = "original_runtime_cpu"
                LOGGER.warning("Android OCR unavailable; using original CPU session: %s", error)
                return self._fallback().run(output_names, input_feed, run_options)

    def __getattr__(self, name):
        if name.startswith("_"):
            raise AttributeError(name)
        return getattr(self._fallback(), name)

    def __del__(self):
        self._close()


def install():
    """安装 OCR 专用会话工厂；非 OCR 模型、内存模型和未匹配版本使用原工厂。

    Installs an OCR-only session factory; other models, memory models, and mismatched versions
    keep the original factory.
    """
    address = os.environ.get("AZURPILOT_OCR_ADDRESS")
    token = os.environ.get("AZURPILOT_ANDROID_TOKEN")
    if not address or not token:
        return
    import onnxruntime as ort

    original = ort.InferenceSession
    if getattr(original, "_android_ocr_factory", False):
        return

    def create_session(*args, **kwargs):
        model = args[0] if args else kwargs.get("path_or_bytes")
        if isinstance(model, (str, Path)) and "ocr_models" in Path(model).parts:
            try:
                with Path(model).open("rb") as stream:
                    model_hash = hashlib.file_digest(stream, "sha256").hexdigest()
                return AndroidSession(original, args, kwargs, model_hash, address, token)
            except Exception as error:
                LOGGER.info("OCR model stays on the original runtime: %s", error)
        return original(*args, **kwargs)

    class SessionMeta(type(original)):
        """保留上游自定义会话的 isinstance 校验。 / Preserves upstream custom-session type checks."""

        def __instancecheck__(cls, instance):
            return isinstance(instance, (original, AndroidSession))

    class SessionFactory(original, metaclass=SessionMeta):
        """以类形式提供会话工厂，避免破坏类型判断。 / Keeps the session factory a class."""

        _android_ocr_factory = True

        def __new__(cls, *args, **kwargs):
            return create_session(*args, **kwargs)

    ort.InferenceSession = SessionFactory


def _read_host_json(method, **fields):
    address = os.environ["AZURPILOT_OCR_ADDRESS"]
    host, port = address.rsplit(":", 1)
    with socket.create_connection((host, int(port)), timeout=5) as connection:
        request = {"method": method, "token": os.environ["AZURPILOT_ANDROID_TOKEN"], **fields}
        connection.sendall(json.dumps(request).encode() + b"\n")
        with connection.makefile("rb") as reader:
            line = reader.readline(MAX_HEADER + 1)
            if not line.endswith(b"\n") or len(line) > MAX_HEADER:
                raise ValueError("Invalid OCR status response")
            reply = json.loads(line)
            if not reply.get("ok"):
                raise RuntimeError(reply.get("error", "Android OCR status failed"))
            return reply


def read_host_status():
    """读取宿主状态，不输出认证口令。

    Reads host status without exposing the authentication token.
    """
    return _read_host_json("status")["status"]


def self_test(model_hash, sample_path="android_ocr_sample.png"):
    """通过 AP 的识别器工厂、预处理和解码验证真实宿主调用，不改实例配置。

    Uses AP's recognizer factory, preprocessing, and decoding to verify host calls without
    changing instance settings. Missing or changed weights and original-runtime fallback fail
    the integration test rather than falsely reporting a connected host.
    """
    from module.ocr.al_ocr import OcrSettings, _create_ocr, _get_onnx_model_params

    spec = _read_host_json("describe", model_sha256=model_hash)["model"]
    versions = {
        "PP-OCRv6_tiny_rec.onnx": ("ppocr_v6", "lite"),
        "PP-OCRv6_small_rec.onnx": ("ppocr_v6", "standard"),
        "alocr-en-us-v2.6.nvc.onnx": ("azur_lane", "alocr_en_v2_6"),
        "alocr-zh-cn-v3.dtk.onnx": ("cn", "alocr_cn_v3"),
    }
    name, version = versions[Path(spec["asset"]).name]
    settings = OcrSettings(backend="onnxruntime", device="cpu",
                           allow_vendor_execution_providers=False, model_version=version)
    model_path = Path(_get_onnx_model_params(name, settings)[0])
    with model_path.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != model_hash:
            raise RuntimeError("AP model differs from the APK; this version stays on original CPU")
    previous_flag = os.environ.get("AZURPILOT_OCR_TEST")
    os.environ["AZURPILOT_OCR_TEST"] = "1"
    try:
        started = time.perf_counter()
        recognizer = _create_ocr(name, settings)
        session = recognizer.text_rec.session.session
        if not isinstance(session, AndroidSession):
            raise RuntimeError("AP OCR did not use the Android session factory")
        result = recognizer(str(sample_path), use_det=False, use_cls=False, use_rec=True)
        elapsed_ms = (time.perf_counter() - started) * 1000
        if session._failed or session._host_runs == 0:
            raise RuntimeError("AP OCR fell back to its original runtime; host integration failed")
        texts = list(result.txts or [])
        return {"ok": True, "model_sha256": model_hash, "backend": session._last_backend,
                "elapsed_ms": elapsed_ms, "texts": texts, "expected_text": "12345",
                "sample_text_matches": "".join(texts).strip() == "12345",
                "host_runs": session._host_runs, "instance_settings_changed": False}
    finally:
        if previous_flag is None:
            os.environ.pop("AZURPILOT_OCR_TEST", None)
        else:
            os.environ["AZURPILOT_OCR_TEST"] = previous_flag


def main():
    """输出状态或带固定标记的 AP 测试结果，不输出认证口令。

    Prints status or a marked AP integration result without exposing authentication tokens.
    """
    import argparse

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--model-sha256")
    args = parser.parse_args()
    if args.self_test:
        try:
            if not args.model_sha256:
                raise ValueError("Select a bundled recognition model")
            result = self_test(args.model_sha256)
        except Exception as error:
            result = {"ok": False, "error": str(error)[:500]}
        print("ANDROID_OCR_TEST=" + json.dumps(result, ensure_ascii=False))
    else:
        print(json.dumps(read_host_status(), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    # sitecustomize 已导入规范模块，复用它避免 -m 的第二份类身份破坏 isinstance。
    import android_ocr

    android_ocr.main()

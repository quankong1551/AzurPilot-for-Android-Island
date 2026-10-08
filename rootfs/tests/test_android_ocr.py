"""验证 APK OCR 张量协议、版本门控和 CPU 回退。

Verifies the APK OCR tensor protocol, version gating, and CPU fallback.
"""

import hashlib
import importlib.util
import json
import os
import socketserver
import sys
import tempfile
import threading
import dataclasses
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import numpy as np

SPEC = importlib.util.spec_from_file_location("android_ocr", Path(__file__).parents[1] / "overlays/android_ocr.py")
ocr = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ocr)
MODEL_HASH = hashlib.sha256(b"weights").hexdigest()
METADATA = {
    "asset": "models/azur_lane/alocr-en-us-v2.6.nvc.onnx",
    "inputs": [{"name": "x", "type": "tensor(float)", "shape": [1, 3, 48, 320]}],
    "outputs": [{"name": "y", "type": "tensor(float)", "shape": [1, 3, 48, 320]}],
    "custom_metadata_map": {"character": "a\nb"},
}


class CpuSession:
    """记录惰性回退次数。 / Records lazy fallback construction."""
    created = 0

    def __init__(self, *args, **kwargs):
        type(self).created += 1

    def run(self, output_names, input_feed, run_options=None):
        return [next(iter(input_feed.values())) * -3]


class Handler(socketserver.StreamRequestHandler):
    """返回可分片的合成张量。 / Returns fragmentable synthetic tensors."""

    def handle(self):
        request = json.loads(self.rfile.readline())
        if self.server.drop_once:
            self.server.drop_once = False
            return
        if request["token"] != "secret" or request.get("model_sha256", MODEL_HASH) != MODEL_HASH:
            reply, data = {"ok": False, "error": "Unknown model or token"}, b""
        elif request["method"] == "status":
            reply, data = {"ok": True, "status": {"vendor": "mediatek", "nnapi": False}}, b""
        elif request["method"] == "describe":
            reply, data = {"ok": True, "model": METADATA}, b""
        else:
            self.server.last_source = request.get("source")
            values = np.frombuffer(self.rfile.read(request["length"]), dtype="<f4").reshape(request["shape"])
            data = (values * 2).tobytes()
            shape = [-1] if self.server.malformed else request["shape"]
            reply = {"ok": True, "backend": "onnx_cpu", "length": len(data), "outputs": [{"name": "y", "shape": shape}]}
            if self.server.truncated:
                data = data[:4]
        wire = json.dumps(reply).encode() + b"\n" + data
        # 小片写入覆盖读缓冲与二进制边界；不是镜像客户端的单次发送。
        for offset in range(0, len(wire), 731):
            self.wfile.write(wire[offset:offset + 731])
        self.wfile.flush()


class OcrProtocolTest(unittest.TestCase):
    """覆盖正常响应、网络中断、坏帧和工厂兼容。 / Covers responses, disconnects, malformed frames, and factory compatibility."""

    @classmethod
    def setUpClass(cls):
        cls.server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler)
        cls.server.daemon_threads = True
        cls.worker = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.worker.start()
        cls.address = f"127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.worker.join()

    def setUp(self):
        self.server.drop_once = self.server.malformed = self.server.truncated = False
        CpuSession.created = 0
        self.values = np.linspace(-1, 1, 3 * 48 * 320, dtype=np.float32).reshape(1, 3, 48, 320)

    def session(self, **kwargs):
        return ocr.AndroidSession(CpuSession, ("model.onnx",), {}, kwargs.get("hash", MODEL_HASH),
                                  self.address, kwargs.get("token", "secret"))

    def test_fragmented_tensor_and_metadata(self):
        session = self.session()
        self.assertEqual(session.get_inputs()[0].name, "x")
        self.assertEqual(session.get_modelmeta().custom_metadata_map["character"], "a\nb")
        np.testing.assert_array_equal(session.run(["y"], {"x": self.values})[0], self.values * 2)
        self.assertIsNone(session._socket)
        self.assertEqual(CpuSession.created, 0)
        self.assertEqual(session._last_backend, "onnx_cpu")
        self.assertEqual(session._host_runs, 1)
        self.assertEqual(self.server.last_source, "ap")

    def test_transient_disconnect_retries(self):
        session = self.session()
        self.server.drop_once = True
        np.testing.assert_array_equal(session.run(None, {"x": self.values})[0], self.values * 2)
        self.assertFalse(session._failed)

    def test_truncated_payload_falls_back_once(self):
        session = self.session()
        self.server.truncated = True
        for _ in range(2):
            np.testing.assert_array_equal(session.run(None, {"x": self.values})[0], self.values * -3)
        self.assertEqual(CpuSession.created, 1)

    def test_malformed_shape_falls_back(self):
        session = self.session()
        self.server.malformed = True
        np.testing.assert_array_equal(session.run(None, {"x": self.values})[0], self.values * -3)

    def test_wrong_token_and_unknown_hash_rejected(self):
        for kwargs in [{"token": "wrong"}, {"hash": "wrong"}]:
            with self.assertRaisesRegex(RuntimeError, "Unknown model"):
                self.session(**kwargs)

    def test_status_uses_host_environment(self):
        with patch.dict(os.environ, {"AZURPILOT_OCR_ADDRESS": self.address,
                                    "AZURPILOT_ANDROID_TOKEN": "secret"}):
            self.assertEqual(ocr.read_host_status(), {"vendor": "mediatek", "nnapi": False})

    def test_factory_hash_gates_models_and_preserves_type_checks(self):
        ort = SimpleNamespace(InferenceSession=CpuSession)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            folder = root / "ocr_models"
            folder.mkdir()
            model = folder / "model.onnx"
            model.write_bytes(b"weights")
            with patch.dict(sys.modules, {"onnxruntime": ort}), patch.dict(os.environ, {
                "AZURPILOT_OCR_ADDRESS": self.address, "AZURPILOT_ANDROID_TOKEN": "secret",
            }):
                ocr.install()
                factory = ort.InferenceSession
                session = factory(str(model))
                self.assertIsInstance(session, ocr.AndroidSession)
                self.assertIsInstance(session, factory)
                ocr.install()
                self.assertIs(ort.InferenceSession, factory)
                model.write_bytes(b"updated model")
                self.assertIsInstance(factory(str(model)), CpuSession)
                self.assertIsInstance(factory(b"memory model"), CpuSession)
                self.assertIsInstance(factory(str(root / "other.onnx")), CpuSession)

    def ap_test(self, changed_model=False, original_cpu=False, fail_host=False):
        """模拟 AP 工厂契约，验证接线测试不会接受静默回退。

        Simulates the AP factory contract to reject silent fallback during integration tests.
        """
        with tempfile.TemporaryDirectory() as directory:
            model = Path(directory) / "ocr_models" / "test.onnx"
            model.parent.mkdir()
            model.write_bytes(b"changed" if changed_model else b"weights")

            @dataclasses.dataclass
            class Settings:
                backend: str
                device: str
                allow_vendor_execution_providers: bool
                model_version: str

            def create(name, settings):
                self.assertEqual((name, settings.model_version), ("azur_lane", "alocr_en_v2_6"))
                session = CpuSession() if original_cpu else self.session()
                rec = SimpleNamespace(session=SimpleNamespace(session=session))

                class Recognizer:
                    text_rec = rec

                    def __call__(inner, path, **flags):
                        self.assertEqual(flags, {"use_det": False, "use_cls": False, "use_rec": True})
                        self.server.truncated = fail_host
                        session.run(None, {"x": self.values})
                        return SimpleNamespace(txts=["12345"])

                return Recognizer()

            upstream = SimpleNamespace(OcrSettings=Settings, _create_ocr=create,
                                       _get_onnx_model_params=lambda *args: (str(model), None, None))
            with patch.dict(sys.modules, {"module.ocr.al_ocr": upstream}), patch.dict(os.environ, {
                "AZURPILOT_OCR_ADDRESS": self.address, "AZURPILOT_ANDROID_TOKEN": "secret",
                "AZURPILOT_OCR_TEST": "previous",
            }):
                result = ocr.self_test(MODEL_HASH)
                self.assertEqual(os.environ["AZURPILOT_OCR_TEST"], "previous")
                return result

    def test_ap_integration_records_actual_host_backend_without_business_calls(self):
        result = self.ap_test()
        self.assertTrue(result["ok"])
        self.assertTrue(result["sample_text_matches"])
        self.assertEqual(result["backend"], "onnx_cpu")
        self.assertEqual(result["host_runs"], 1)
        self.assertFalse(result["instance_settings_changed"])
        self.assertEqual(self.server.last_source, "diagnostic")

    def test_ap_integration_rejects_changed_model(self):
        with self.assertRaisesRegex(RuntimeError, "AP model differs"):
            self.ap_test(changed_model=True)

    def test_ap_integration_rejects_unpatched_factory(self):
        with self.assertRaisesRegex(RuntimeError, "session factory"):
            self.ap_test(original_cpu=True)

    def test_ap_integration_rejects_silent_original_runtime_fallback(self):
        with self.assertRaisesRegex(RuntimeError, "host integration failed"):
            self.ap_test(fail_host=True)

    def test_real_onnx_session_accepts_factory_and_fallback_arguments(self):
        """用真实 ORT 验证会话类型和惰性回退参数。

        Checks session types and lazy fallback arguments with real ONNX Runtime.
        """
        try:
            import onnx
            import onnxruntime as ort
        except ImportError:
            self.skipTest("Real session integration requires onnx and onnxruntime")
        original = ort.InferenceSession
        shape = [1, 3, 48, 320]
        graph = onnx.helper.make_graph(
            [onnx.helper.make_node("Mul", ["x", "scale"], ["y"])], "fallback",
            [onnx.helper.make_tensor_value_info("x", onnx.TensorProto.FLOAT, shape)],
            [onnx.helper.make_tensor_value_info("y", onnx.TensorProto.FLOAT, shape)],
            [onnx.helper.make_tensor("scale", onnx.TensorProto.FLOAT, [1], [-3])],
        )
        model = onnx.helper.make_model(graph, opset_imports=[onnx.helper.make_opsetid("", 13)], ir_version=10)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "ocr_models" / "test.onnx"
            path.parent.mkdir()
            onnx.save(model, path)
            checksum = hashlib.sha256(path.read_bytes()).hexdigest()
            options = ort.SessionOptions()
            options.intra_op_num_threads = 1
            with patch.dict(globals(), {"MODEL_HASH": checksum}), patch.object(ort, "InferenceSession", original), \
                    patch.dict(os.environ, {"AZURPILOT_OCR_ADDRESS": self.address,
                                            "AZURPILOT_ANDROID_TOKEN": "secret"}):
                ocr.install()
                session = ort.InferenceSession(str(path), sess_options=options, providers=["CPUExecutionProvider"])
                self.assertIsInstance(session, ort.InferenceSession)
                np.testing.assert_array_equal(session.run(None, {"x": self.values})[0], self.values * 2)
                self.server.truncated = True
                np.testing.assert_array_equal(session.run(None, {"x": self.values})[0], self.values * -3)
                self.assertIsInstance(session._cpu, original)


if __name__ == "__main__":
    unittest.main()

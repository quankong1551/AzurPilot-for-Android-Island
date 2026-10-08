#!/usr/bin/env python3
"""转换并校验 APK 内置 OCR 模型，保留 AP 的 ONNX 输入输出契约。

在 Linux 的 Python 3.12 环境中安装 onnx2tf==2.6.9 后运行。转换后的 FP32 模型
必须通过 ONNX 与 LiteRT CPU 数值对照；该检查不代替设备上的 NPU 精度测试。

Converts and verifies bundled OCR models while preserving AP's ONNX I/O contract.
Run on Linux with Python 3.12 and onnx2tf==2.6.9. Converted FP32 models must pass
ONNX/LiteRT CPU comparisons; this does not replace NPU accuracy tests on devices.
"""

import argparse
import copy
import hashlib
import json
import subprocess
import sys
from pathlib import Path

import flatbuffers
import numpy as np
import onnx
import onnxruntime as ort
from onnxsim import simplify
from ai_edge_litert.interpreter import Interpreter
from ai_edge_litert import schema_py_generated as schema


ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/app/src/main/assets/ocr"
UPSTREAM = "1841cb1941751a81ab70668b4d2383c369c4506e"
SOURCES = {
    "ppocr-v6/PP-OCRv6_tiny_rec.onnx": "9ef676d6ed3c88256a2d92c640c44f25b0c40947e111b14b8be8f594091563e6",
    "ppocr-v6/PP-OCRv6_small_rec.onnx": "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634",
    "azur_lane/alocr-en-us-v2.6.nvc.onnx": "35413805f63b5fb13a0060b1cf3d5fcefb2fba6b88a3e85a1e1ae236533af5f6",
    "zh-CN/alocr-zh-cn-v3.dtk.onnx": "d0cd4977b80b1138239f88ce6b5de8e421d508fdf6da63b744e6077a4e2af0f3",
    "det/PP-OCRv6_tiny_det.onnx": "193bab7a04fca699a6c82e6abb5b81bdb28177f0abd4062552b04908dafb19f8",
}


def sha256(path):
    """返回文件内容哈希。 / Returns the file's content hash."""
    return hashlib.sha256(path.read_bytes()).hexdigest()


def node_info(node):
    """导出供 Python 会话代理使用的元数据。 / Exports metadata for the Python session proxy."""
    return {
        "name": node.name,
        "type": "tensor(float)",
        "shape": [d.dim_value or d.dim_param or None for d in node.type.tensor_type.shape.dim],
    }


def normalize_batch_matmul_ranks(data):
    """为常量矩阵补前导单维，使 BatchMatMul 两输入具有相同秩。

    只克隆静态 FP32 常量的张量描述，复用原缓冲区，不修改其他消费者或乘法选项。
    动态输入、不支持的类型和外置缓冲区拒绝改写；已经规范化的模型保持原字节。

    Prepends singleton dimensions to constant matrices so BatchMatMul input ranks match.
    Clones static FP32 tensor descriptors and shares buffers without changing other consumers
    or multiplication options. Rejects dynamic inputs, unsupported types, and external buffers;
    already normalized models retain their original bytes.
    """
    model = schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(data, 0))
    changed = 0
    for graph in model.subgraphs:
        for op in graph.operators:
            code = model.operatorCodes[op.opcodeIndex]
            if max(code.builtinCode, code.deprecatedBuiltinCode) != schema.BuiltinOperator.BATCH_MATMUL:
                continue
            inputs = [graph.tensors[int(index)] for index in op.inputs]
            ranks = [len(tensor.shape) for tensor in inputs]
            if len(ranks) != 2 or not all(2 <= rank <= 4 for rank in ranks):
                raise ValueError(f"Unsupported BatchMatMul input ranks: {ranks}")
            if ranks[0] == ranks[1]:
                continue
            slot = int(ranks[1] < ranks[0])
            tensor = inputs[slot]
            buffer = model.buffers[tensor.buffer]
            if (tensor.type != schema.TensorType.FLOAT32 or tensor.isVariable or
                    buffer.data is None or len(buffer.data) == 0 or any(d <= 0 for d in tensor.shape)):
                raise ValueError("Mismatched BatchMatMul ranks require a static FP32 constant")
            aligned = copy.deepcopy(tensor)
            padding = [1] * (max(ranks) - ranks[slot])
            aligned.shape = padding + list(tensor.shape)
            if aligned.shapeSignature is not None:
                aligned.shapeSignature = padding + list(tensor.shapeSignature)
            aligned.name = (tensor.name or b"constant") + b".npu_batch_rank"
            op.inputs = list(op.inputs)
            op.inputs[slot] = len(graph.tensors)
            graph.tensors.append(aligned)
            changed += 1
    if not changed:
        return data, 0
    # 重打包会移动偏移量，不能悄悄损坏未嵌入 FlatBuffer 的权重。
    if any(getattr(buffer, "offset", 0) or getattr(buffer, "size", 0) for buffer in model.buffers):
        raise ValueError("External LiteRT buffers cannot be repacked")
    builder = flatbuffers.Builder(len(data))
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    return bytes(builder.Output()), changed


def check_conversion(source, converted):
    """对照空白和随机张量，拒绝布局、算子或输出顺序错误。

    Compares blank and random tensors, rejecting layout, operator, or output-order errors.
    """
    original = ort.InferenceSession(str(source), providers=["CPUExecutionProvider"])
    lite = Interpreter(model_path=str(converted), num_threads=2)
    lite.allocate_tensors()
    _, unnormalized = normalize_batch_matmul_ranks(converted.read_bytes())
    if unnormalized:
        raise ValueError("OCR BatchMatMul input ranks must be normalized before packaging")
    if any(op["op_name"] == "CUSTOM" for op in lite._get_ops_details()):
        raise ValueError("OCR conversions must not contain custom operators before NPU compilation")
    inp = lite.get_input_details()[0]
    out = lite.get_output_details()[0]
    shape = tuple(inp["shape"].tolist())
    if shape == (1, 48, 320, 3):
        layout = "nhwc"
    elif shape == (1, 3, 48, 320):
        layout = "nchw"
    else:
        raise ValueError(f"Unexpected LiteRT input: {shape}")
    rng = np.random.default_rng(20261007)
    worst_error = 0.0
    for values in [np.zeros((1, 3, 48, 320), np.float32),
                   np.full((1, 3, 48, 320), -1.0, np.float32),
                   rng.uniform(-1, 1, (1, 3, 48, 320)).astype(np.float32)]:
        expected = original.run(None, {original.get_inputs()[0].name: values})[0]
        lite.set_tensor(inp["index"], values.transpose(0, 2, 3, 1) if layout == "nhwc" else values)
        lite.invoke()
        actual = lite.get_tensor(out["index"])
        np.testing.assert_allclose(actual, expected, rtol=1e-3, atol=1e-4)
        np.testing.assert_array_equal(actual.argmax(-1), expected.argmax(-1))
        worst_error = max(worst_error, float(np.max(np.abs(actual - expected))))
    return {
        "asset": converted.relative_to(ASSETS).as_posix(),
        "sha256": sha256(converted),
        "input_layout": layout,
        "input_shape": list(shape),
        "output_shape": out["shape"].tolist(),
        "input_name": inp["name"],
        "output_name": out["name"],
        "cpu_max_abs_error": worst_error,
    }


def main():
    """生成转换产物和白名单；可仅修正常量秩，或只复验现有产物。

    Generates conversions and the allowlist; can normalize existing constants or only reverify.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--verify-only", action="store_true")
    mode.add_argument("--normalize-existing", action="store_true",
                      help="Normalize bundled LiteRT constants and revalidate without reconverting ONNX")
    args = parser.parse_args()
    previous = {}
    if (ASSETS / "manifest.json").is_file():
        previous = {item["sha256"]: item for item in
                    json.loads((ASSETS / "manifest.json").read_text())["models"]}
    models = []
    for relative, expected_hash in SOURCES.items():
        source = ASSETS / "models" / relative
        if sha256(source) != expected_hash:
            raise ValueError(f"Upstream model hash mismatch: {relative}")
        model = onnx.load(source)
        item = {
            "asset": source.relative_to(ASSETS).as_posix(),
            "sha256": expected_hash,
            "inputs": [node_info(n) for n in model.graph.input],
            "outputs": [node_info(n) for n in model.graph.output],
            "custom_metadata_map": {p.key: p.value for p in model.metadata_props},
        }
        if "_rec.onnx" in relative or "alocr-" in relative:
            converted = ASSETS / "litert" / (source.stem + ".tflite")
            if not args.verify_only and not args.normalize_existing:
                work = ROOT / ".tmp/ocr-convert" / source.stem
                work.mkdir(parents=True, exist_ok=True)
                # 转换器可能原地简化 ONNX；原始资产的哈希必须与 AP 的模型保持一致。
                conversion_input = work / source.name
                conversion_model = onnx.load(source)
                for dim, value in zip(conversion_model.graph.input[0].type.tensor_type.shape.dim,
                                      (1, 3, 48, 320), strict=True):
                    dim.dim_value = value
                del conversion_model.graph.value_info[:]
                conversion_model, valid = simplify(conversion_model)
                if not valid:
                    raise ValueError(f"ONNX simplification changed outputs: {relative}")
                onnx.save(conversion_model, conversion_input)
                log = work / "conversion.log"
                with log.open("w") as output:
                    subprocess.run([
                        sys.executable, "-m", "onnx2tf", "-i", str(conversion_input), "-o", str(work),
                        "-ois", "x:1,3,48,320", "-coion", "-v", "error",
                    ], check=True, stdout=output, stderr=subprocess.STDOUT)
                generated = list(work.glob("*_float32.tflite"))
                if len(generated) != 1:
                    raise ValueError(f"Expected one FP32 conversion; inspect {log}")
                converted.parent.mkdir(parents=True, exist_ok=True)
                converted.write_bytes(generated[0].read_bytes())
            if not args.verify_only:
                normalized, count = normalize_batch_matmul_ranks(converted.read_bytes())
                if count:
                    converted.write_bytes(normalized)
                print(f"OCR_MATMUL_RANKS_NORMALIZED {source.name}: {count}")
            item["litert"] = check_conversion(source, converted)
            if "mnn" in previous.get(expected_hash, {}):
                item["mnn"] = previous[expected_hash]["mnn"]
            print(f"OCR_CONVERSION_OK {source.name}: {item['litert']['cpu_max_abs_error']:.8g}")
        models.append(item)
    manifest = {"version": 1, "upstream_commit": UPSTREAM, "converter": "onnx2tf==2.6.9", "models": models}
    if args.verify_only:
        existing = json.loads((ASSETS / "manifest.json").read_text())
        for old, new in zip(existing["models"], models, strict=True):
            assert old["sha256"] == new["sha256"]
            assert old.get("litert", {}).get("sha256") == new.get("litert", {}).get("sha256")
    else:
        (ASSETS / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""验证 MTK 矩阵秩修正保留广播、转置、共享常量和模型输出。

Tests that MTK matrix rank normalization preserves broadcasting, transposition,
shared constants, and model outputs. Runs with the Linux OCR conversion dependencies.
"""

import unittest

import flatbuffers
import numpy as np
from ai_edge_litert import schema_py_generated as schema
from ai_edge_litert.interpreter import Interpreter

from prepare_ocr_models import normalize_batch_matmul_ranks


def pack(model):
    """生成可执行的小型 LiteRT 模型。 / Builds an executable small LiteRT model."""
    builder = flatbuffers.Builder(1024)
    builder.Finish(model.Pack(builder), file_identifier=b"TFL3")
    return bytes(builder.Output())


def fixture(constant_slot=1, batch=(2,), adj_x=False, adj_y=False):
    """构造两个输入秩不同且常量同时为输出的矩阵乘法。

    Builds a matrix multiplication with mismatched input ranks and a shared constant output.
    """
    shapes = [(*batch, 5, 3), (*batch, 3, 4)]
    shapes[constant_slot] = shapes[constant_slot][-2:]
    values = [np.arange(np.prod(shape), dtype=np.float32).reshape(shape) / 100 for shape in shapes]
    for slot, transpose in enumerate([adj_x, adj_y]):
        if transpose:
            values[slot] = values[slot].swapaxes(-1, -2).copy()
    model = schema.ModelT()
    model.version = 3
    model.buffers = [schema.BufferT(), schema.BufferT()]
    model.buffers[1].data = np.frombuffer(values[constant_slot].tobytes(), dtype=np.uint8)
    code = schema.OperatorCodeT()
    code.builtinCode = schema.BuiltinOperator.BATCH_MATMUL
    code.deprecatedBuiltinCode = min(127, code.builtinCode)
    code.version = 1
    model.operatorCodes = [code]
    graph = schema.SubGraphT()
    graph.tensors = []
    for slot, shape in enumerate([values[0].shape, values[1].shape, (*batch, 5, 4)]):
        tensor = schema.TensorT()
        tensor.name = f"tensor{slot}".encode()
        tensor.type = schema.TensorType.FLOAT32
        tensor.shape = list(shape)
        tensor.buffer = int(slot == constant_slot)
        graph.tensors.append(tensor)
    op = schema.OperatorT()
    op.inputs, op.outputs = [0, 1], [2]
    op.builtinOptionsType = schema.BuiltinOptions.BatchMatMulOptions
    op.builtinOptions = schema.BatchMatMulOptionsT()
    op.builtinOptions.adjX, op.builtinOptions.adjY = adj_x, adj_y
    graph.operators = [op]
    graph.inputs = [1 - constant_slot]
    graph.outputs = [2, constant_slot]
    model.subgraphs = [graph]
    return model, values[1 - constant_slot]


def run(data, value):
    """通过真实 LiteRT CPU 执行图。 / Executes a graph with the real LiteRT CPU runtime."""
    interpreter = Interpreter(model_content=data, num_threads=1)
    interpreter.allocate_tensors()
    interpreter.set_tensor(interpreter.get_input_details()[0]["index"], value)
    interpreter.invoke()
    return [interpreter.get_tensor(output["index"]) for output in interpreter.get_output_details()]


class OcrMatrixRanksTest(unittest.TestCase):
    """钉住模型修正的输出契约与拒绝条件。

    Pins output contracts and rejection conditions for model normalization.
    """

    def test_broadcast_transpose_and_shared_output_preserved(self):
        for slot in [0, 1]:
            for batch in [(2,), (2, 3)]:
                for adj_x, adj_y in [(False, False), (True, False), (False, True), (True, True)]:
                    with self.subTest(slot=slot, batch=batch, adj_x=adj_x, adj_y=adj_y):
                        model, values = fixture(slot, batch, adj_x, adj_y)
                        original = pack(model)
                        normalized, count = normalize_batch_matmul_ranks(original)
                        self.assertEqual(count, 1)
                        for actual, expected in zip(run(normalized, values), run(original, values), strict=True):
                            np.testing.assert_allclose(actual, expected, rtol=1e-6, atol=1e-6)
                        restored = schema.ModelT.InitFromObj(schema.Model.GetRootAsModel(normalized, 0))
                        self.assertEqual(list(restored.subgraphs[0].tensors[slot].shape),
                                         list(model.subgraphs[0].tensors[slot].shape))
                        self.assertEqual(normalize_batch_matmul_ranks(normalized), (normalized, 0))

    def test_dynamic_matrix_rejected(self):
        model, _ = fixture()
        model.subgraphs[0].tensors[1].buffer = 0
        with self.assertRaisesRegex(ValueError, "static FP32 constant"):
            normalize_batch_matmul_ranks(pack(model))

    def test_non_fp32_matrix_rejected(self):
        model, _ = fixture()
        model.subgraphs[0].tensors[1].type = schema.TensorType.FLOAT16
        with self.assertRaisesRegex(ValueError, "static FP32 constant"):
            normalize_batch_matmul_ranks(pack(model))

    def test_variable_matrix_rejected(self):
        model, _ = fixture()
        model.subgraphs[0].tensors[1].isVariable = True
        with self.assertRaisesRegex(ValueError, "static FP32 constant"):
            normalize_batch_matmul_ranks(pack(model))

    def test_external_buffer_rejected(self):
        model, _ = fixture()
        model.buffers[1].offset, model.buffers[1].size = 4096, 48
        with self.assertRaisesRegex(ValueError, "External LiteRT buffers"):
            normalize_batch_matmul_ranks(pack(model))

    def test_invalid_rank_rejected(self):
        model, _ = fixture()
        model.subgraphs[0].tensors[1].shape = [12]
        with self.assertRaisesRegex(ValueError, "input ranks"):
            normalize_batch_matmul_ranks(pack(model))


if __name__ == "__main__":
    unittest.main()

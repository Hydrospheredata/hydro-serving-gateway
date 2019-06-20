package io.hydrosphere.serving.gateway.execution

import com.google.protobuf.ByteString
import io.hydrosphere.serving.gateway.GenericTest
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.tensor.{TensorProto, Uint8Tensor}
import io.hydrosphere.serving.tensorflow.tensor_shape.TensorShapeProto
import io.hydrosphere.serving.tensorflow.types.DataType.{DT_INT16, DT_STRING}

class RequestValidationSpec extends GenericTest {

  describe("RequestValidator") {
    it("should skip verifications for tensors with non-empty tensorContent field") {
      val ignoreMe = TensorProto(
        dtype = DT_INT16,
        tensorContent = ByteString.copyFromUtf8("testtest")
      )
      val noticeMe = Uint8Tensor(TensorShape.scalar, Seq(1, 2, 3)).toProto
      val request = Map(
        "ignoreMe" -> ignoreMe,
        "noticeMe" -> noticeMe
      )

      val result = RequestValidator.verify(request)
      assert(result.isRight)
      val r = result.right.get
      assert(r("ignoreMe") === ignoreMe)
      assert(r("noticeMe") === noticeMe)
    }

    it("should put actual dims") {
      val data = TensorProto(
        dtype = DT_STRING,
        stringVal = Seq(ByteString.copyFromUtf8("foo")),
        tensorShape = Some(TensorShapeProto(dim= Seq(TensorShapeProto.Dim(size = -1))))
      )
      val request = Map(
        "input" -> data
      )
      val result = RequestValidator.verify(request)
      assert(result.isRight, result)
      val res = result.right.get
      assert(res("input").tensorShape.contains(TensorShapeProto(dim = Seq(TensorShapeProto.Dim(size = 1)))))
    }
  }
}
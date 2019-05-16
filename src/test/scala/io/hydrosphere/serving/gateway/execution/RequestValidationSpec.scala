package io.hydrosphere.serving.gateway.execution

import com.google.protobuf.ByteString
import io.hydrosphere.serving.gateway.GenericTest
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.tensor.{TensorProto, Uint8Tensor}
import io.hydrosphere.serving.tensorflow.types.DataType.DT_INT16

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
  }
}
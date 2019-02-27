package io.hydrosphere.serving.gateway.service

import cats.effect.IO
import cats.implicits._
import com.google.protobuf.ByteString
import io.hydrosphere.serving.gateway.GenericTest
import io.hydrosphere.serving.gateway.service.application.ApplicationExecutionServiceImpl
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest
import io.hydrosphere.serving.tensorflow.tensor.{TensorProto, Uint8Tensor}
import io.hydrosphere.serving.tensorflow.types.DataType.DT_INT16

class ApplicationExecutionServiceSpec extends GenericTest {

  describe("ApplicationExecutionService") {
    it("should skip verifications for tensors with non-empty tensorContent field") {
      val applicationExecutionService = new ApplicationExecutionServiceImpl[IO](
        null, null, null
      )
      val ignoreMe = TensorProto(
        dtype = DT_INT16,
        tensorContent = ByteString.copyFromUtf8("testtest")
      )
      val noticeMe = Uint8Tensor(TensorShape.scalar, Seq(1, 2, 3)).toProto
      val request = PredictRequest(
        inputs = Map(
          "ignoreMe" -> ignoreMe,
          "noticeMe" -> noticeMe
        )
      )
      val result = applicationExecutionService.verify(request)
      assert(result.isRight)
      val r = result.right.get
      assert(r.inputs("ignoreMe") === ignoreMe)
      assert(r.inputs("noticeMe") === noticeMe)
    }
  }
}
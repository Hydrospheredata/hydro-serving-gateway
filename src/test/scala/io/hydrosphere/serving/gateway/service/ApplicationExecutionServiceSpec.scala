package io.hydrosphere.serving.gateway.service

import cats.effect.IO
import cats.implicits._
import com.google.protobuf.ByteString
import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.contract.utils.ContractBuilders
import io.hydrosphere.serving.gateway.GenericTest
import io.hydrosphere.serving.gateway.grpc.{Prediction, PredictionWithMetadata}
import io.hydrosphere.serving.gateway.persistence.application._
import io.hydrosphere.serving.gateway.service.application.{ApplicationExecutionServiceImpl, ExecutionUnit, RequestTracingInfo}
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest
import io.hydrosphere.serving.tensorflow.tensor.{TensorProto, Uint8Tensor}
import io.hydrosphere.serving.tensorflow.types.DataType
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

    it("should pass prediction error to client") {
      val signature = ModelSignature("app",
        Seq(ContractBuilders.simpleTensorModelField("a", DataType.DT_STRING, TensorShape.scalar)),
        Seq(ContractBuilders.simpleTensorModelField("a", DataType.DT_STRING, TensorShape.scalar))
      )
      val contract = ModelContract("app", Some(signature))
      val appStorage = new ApplicationStorage[IO] {
        override def get(name: String): IO[Option[StoredApplication]] = IO(Some(StoredApplication(
          1, "app", None, contract, StoredExecutionGraph(Seq(StoredStage("1", Seq(StoredService(1, 100)), Some(signature))))
        )))

        override def get(id: Long): IO[Option[StoredApplication]] = ???

        override def version: IO[String] = ???

        override def listAll: IO[Seq[StoredApplication]] = ???

        override def update(apps: Seq[StoredApplication], version: String): IO[String] = ???
      }

      val prediction = new Prediction[IO] {
        override def predict(unit: ExecutionUnit, request: PredictRequest, tracingInfo: Option[RequestTracingInfo]): IO[PredictionWithMetadata] = {
          IO.raiseError(new Exception("Some shit happened"))
        }
      }
      val applicationExecutionService = new ApplicationExecutionServiceImpl[IO](
        null, appStorage, prediction
      )
      val ignoreMe = TensorProto(
        dtype = DT_INT16,
        tensorContent = ByteString.copyFromUtf8("testtest")
      )
      val noticeMe = Uint8Tensor(TensorShape.scalar, Seq(1, 2, 3)).toProto
      val request = PredictRequest(
        modelSpec = Some(ModelSpec("app")),
        inputs = Map(
          "ignoreMe" -> ignoreMe,
          "noticeMe" -> noticeMe
        )
      )
      val result = applicationExecutionService.serveGrpcApplication(request, None).unsafeToFuture()
      result.map { _ =>
        fail("I should fail")
      }.failed.map{ x =>
        println(x)
        assert(x.getMessage == "Some shit happened", x)
      }
    }
  }
}
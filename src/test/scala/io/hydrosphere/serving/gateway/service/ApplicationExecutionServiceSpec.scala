package io.hydrosphere.serving.gateway.service

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import com.google.protobuf.ByteString
import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.contract.utils.ContractBuilders
import io.hydrosphere.serving.gateway.GenericTest
import io.hydrosphere.serving.gateway.integrations.Prediction
import io.hydrosphere.serving.gateway.persistence.application._
import io.hydrosphere.serving.gateway.persistence.servable
import io.hydrosphere.serving.gateway.persistence.servable.StoredServable
import io.hydrosphere.serving.gateway.service.application.ApplicationExecutionServiceImpl
import io.hydrosphere.serving.manager.grpc.entities.ModelVersion
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.tensor.{TensorProto, Uint8Tensor}
import io.hydrosphere.serving.tensorflow.types.DataType
import io.hydrosphere.serving.tensorflow.types.DataType.DT_INT16

import scala.concurrent.Future

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
      val client = new PredictDownstream {
        override def send(req: PredictRequest): Future[PredictOut] = ???
        override def close(): Future[Unit] = ???
      }
      val contract = ModelContract("app", Some(signature))
      var storedApplications = Seq(StoredApplication("1", "app", None, contract, Seq(StoredStage("1", NonEmptyList.of(StoredServable("1", 100, 100, ModelVersion(id=1))), signature, client))))

      val appStorage = new servable.ApplicationStorage[IO] {
        override def getByName(name: String): IO[Option[StoredApplication]] = IO.pure(storedApplications.find(_.name == name))

        override def getById(id: String): IO[Option[StoredApplication]] = IO.pure(storedApplications.find(_.id == id))

        override def listAll: IO[Seq[StoredApplication]] = IO.pure(storedApplications)

        override def addApps(apps: Seq[StoredApplication]): IO[Unit] = IO(storedApplications ++ apps).flatMap(_ => IO.unit)

        override def removeApps(ids: Seq[String]): IO[Unit] = IO(storedApplications.filterNot(ids.contains)).flatMap(_ => IO.unit)
      }

      val prediction = new Prediction[IO] {
        override def predict(unit: ExecutionUnit, request: PredictRequest): IO[PredictResponse] = {
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
      val result = applicationExecutionService.serveProtoRequest(request).unsafeToFuture()
      result.map { _ =>
        fail("I should fail")
      }.failed.map{ x =>
        println(x)
        assert(x.getMessage == "Some shit happened", x)
      }
    }
  }
}
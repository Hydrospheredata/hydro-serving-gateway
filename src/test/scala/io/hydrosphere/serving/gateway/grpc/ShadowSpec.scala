package io.hydrosphere.serving.gateway.grpc

import cats.effect.{Clock, IO}
import cats.implicits._
import io.hydrosphere.serving.proto.contract.field.ModelField
import io.hydrosphere.serving.proto.contract.signature.ModelSignature
import io.hydrosphere.serving.gateway.execution.application.{AssociatedResponse, MonitoringClient}
import io.hydrosphere.serving.gateway.execution.grpc.PredictionClient
import io.hydrosphere.serving.gateway.execution.servable.{Predictor, ServableRequest}
import io.hydrosphere.serving.gateway.persistence.{StoredModelVersion, StoredServable}
import io.hydrosphere.monitoring.proto.sonar.entities.{ApplicationInfo, ExecutionMetadata}
import io.hydrosphere.serving.proto.contract.tensor.definitions.Shape
import io.hydrosphere.serving.proto.runtime.api.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.proto.contract.tensor.definitions.StringTensor
import io.hydrosphere.serving.proto.contract.types.DataType
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers


class ShadowSpec extends AnyFunSpec with Matchers {
  implicit val clock = Clock.create[IO]
  it("monitoring shouldn't affect prediction") {
    val contract = ModelSignature(
      signatureName = "predict",
      inputs = Seq(ModelField(
        name = "test",
        shape = None,
        typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_STRING)
      )),
      outputs = Seq(ModelField(
        name = "test",
        shape = None,
        typeOrSubfields = ModelField.TypeOrSubfields.Dtype(DataType.DT_STRING)
      ))
    )
    val servable = StoredServable("servable-1", "host", 42, 100, StoredModelVersion(1, 1, "model", contract, "Ok"))
    val clientCtor = new PredictionClient.Factory[IO] {
      override def make(host: String, port: Int): IO[PredictionClient[IO]] = {
        IO(new PredictionClient[IO] {
          override def predict(request: PredictRequest): IO[PredictResponse] = IO(PredictResponse(request.inputs))

          override def close(): IO[Unit] = IO.unit
        })
      }
    }
    val shadow = new MonitoringClient[IO] {
      override def monitor(request: ServableRequest, response: AssociatedResponse, appInfo: Option[ApplicationInfo]): IO[ExecutionMetadata] = {
        IO.raiseError(new RuntimeException("WTF"))
      }
    }
    val servablePredictor = Predictor.forServable[IO](servable, clientCtor).unsafeRunSync()
    val shadowed = Predictor.withShadow(servable, servablePredictor, shadow, None)

    val request = ServableRequest(
      data = Map(
        "test" -> StringTensor(Shape.scalar, Seq("tset")).toProto
      ),
      requestId = "test-request"
    )
    val response = shadowed.predict(request).unsafeRunSync()
    println(s"RESPONSE ${response.data}")
    assert(response.data.right.get.contains("test"))
  }
}

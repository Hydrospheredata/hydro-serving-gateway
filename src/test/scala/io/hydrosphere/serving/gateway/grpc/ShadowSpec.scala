package io.hydrosphere.serving.gateway.grpc

import cats.effect.{Clock, IO}
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.execution.application.{AssociatedResponse, MonitoringClient}
import io.hydrosphere.serving.gateway.execution.grpc.PredictionClient
import io.hydrosphere.serving.gateway.execution.servable.{Predictor, ServableRequest}
import io.hydrosphere.serving.gateway.persistence.{StoredModelVersion, StoredServable}
import io.hydrosphere.serving.monitoring.metadata.{ApplicationInfo, ExecutionMetadata}
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.tensor.StringTensor
import org.scalatest.{FunSpec, Matchers}


class ShadowSpec extends FunSpec with Matchers {
  implicit val clock = Clock.create[IO]
  it("reqstore shouldn't affect prediction") {
    val servable = StoredServable("host", 42, 100, StoredModelVersion(1, 1, "model", ModelSignature.defaultInstance, "Ok"))
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
        "test" -> StringTensor(TensorShape.scalar, Seq("tset")).toProto
      ),
      requestId = "test-request"
    )
    val response = shadowed.predict(request).unsafeRunSync()
    assert(response.data.right.get.contains("test"))
  }
}

package io.hydrosphere.serving.gateway.execution.servable

import java.util.concurrent.TimeUnit

import cats.Monad
import cats.effect.{Clock, Sync}
import cats.implicits._
import io.hydrosphere.serving.gateway.Logging
import io.hydrosphere.serving.gateway.execution.application.{AssociatedResponse, MonitoringClient}
import io.hydrosphere.serving.gateway.execution.grpc.PredictionClient
import io.hydrosphere.serving.gateway.persistence.StoredServable
import io.hydrosphere.serving.monitoring.metadata.ExecutionMetadata
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest


trait Predictor[F[_]] {
  def predict(request: ServableRequest): F[ServableResponse]
}

trait CloseablePredictor[F[_]] extends Predictor[F] {
  def close: F[Unit]
}

object Predictor extends Logging {
  def forServable[F[_]](
    servable: StoredServable,
    clientCtor: PredictionClient.Factory[F]
  )(
    implicit F: Sync[F],
    clock: Clock[F],
  ): F[CloseablePredictor[F]] = {
    for {
      stub <- clientCtor.make(servable.host, servable.port)
    } yield new CloseablePredictor[F] {
      def predict(request: ServableRequest): F[ServableResponse] = {
        val req = PredictRequest(
          modelSpec = Some(ModelSpec(
            name = servable.modelVersion.name,
            version = servable.modelVersion.version.some,
            signatureName = servable.modelVersion.predict.signatureName
          )),
          inputs = request.data
        )
        for {
          start <- clock.monotonic(TimeUnit.MILLISECONDS)
          res <- stub.predict(req).attempt
          end <- clock.monotonic(TimeUnit.MILLISECONDS)
        } yield ServableResponse(
          data = res.map(_.outputs),
          latency = end - start
        )
      }

      override def close: F[Unit] = stub.close()
    }
  }

  def withShadow[F[_] : Sync](
    servable: StoredServable,
    servableExec: Predictor[F],
    shadow: MonitoringClient[F]
  ): Predictor[F] = {
    new Predictor[F] {
      override def predict(request: ServableRequest): F[ServableResponse] = {
        for {
          res <- servableExec.predict(request)
          _ <- shadow.monitor(request, AssociatedResponse(res, servable), None)
            .handleErrorWith { err =>
              Logging.error("Error while sending data to monitoring", err)
                .as(ExecutionMetadata.defaultInstance)
            }
        } yield res
      }
    }
  }
}
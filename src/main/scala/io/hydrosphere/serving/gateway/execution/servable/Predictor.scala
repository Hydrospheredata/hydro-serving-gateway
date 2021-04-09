package io.hydrosphere.serving.gateway.execution.servable

import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.implicits._
import io.hydrosphere.serving.gateway.{Contract, Logging}
import io.hydrosphere.serving.gateway.execution.application.{AssociatedResponse, MonitoringClient}
import io.hydrosphere.serving.gateway.execution.grpc.PredictionClient
import io.hydrosphere.serving.gateway.persistence.StoredServable
import io.hydrosphere.monitoring.proto.sonar.entities.{ApplicationInfo, ExecutionMetadata}
import io.hydrosphere.serving.proto.runtime.api.PredictRequest


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
        val req = PredictRequest(inputs = request.data)
        for {
          validatedInput <- F.fromEither(Contract.validate(req.inputs, servable.modelVersion.signature.inputs.toList))
          start <- clock.monotonic(TimeUnit.MILLISECONDS)
          res <- stub.predict(req.copy(inputs = validatedInput)).attempt
          end <- clock.monotonic(TimeUnit.MILLISECONDS)
          maybeValidatedOutput = res.flatMap(d => Contract.validate(d.outputs, servable.modelVersion.signature.outputs.toList))
        } yield ServableResponse(
          data = maybeValidatedOutput,
          latency = end - start
        )
      }

      override def close: F[Unit] = stub.close()
    }
  }

  def withShadow[F[_] : Sync](
    servable: StoredServable,
    servableExec: Predictor[F],
    shadow: MonitoringClient[F],
    appInfo: Option[ApplicationInfo]
  ): Predictor[F] = {
    new Predictor[F] {
      override def predict(request: ServableRequest): F[ServableResponse] = {
        for {
          res <- servableExec.predict(request)
          _ <- shadow.monitor(request, AssociatedResponse(res, servable), appInfo)
            .handleErrorWith { err =>
              Logging.error("Error while sending data to monitoring", err)
                .as(ExecutionMetadata.defaultInstance)
            }
        } yield res
      }
    }
  }
}
package io.hydrosphere.serving.gateway.execution.servable

import java.util.concurrent.TimeUnit

import cats.effect.{Clock, Sync}
import cats.implicits._
import io.hydrosphere.serving.gateway.execution.Types.MessageData
import io.hydrosphere.serving.gateway.execution.grpc.PredictionClient
import io.hydrosphere.serving.gateway.persistence.StoredServable
import io.hydrosphere.serving.tensorflow.api.model.ModelSpec
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest

trait ServableExec[F[_]] {
  def predict(data: MessageData): F[ServableResponse]
}

object ServableExec {
  def forServable[F[_]](
    servable: StoredServable,
    clientCtor: PredictionClient.Factory[F]
  )(
    implicit F: Sync[F],
    clock: Clock[F],
  ): F[CloseableExec[F]] = {
    for {
      stub <- clientCtor.make(servable.host, servable.port)
    } yield new CloseableExec[F] {
      def predict(data: MessageData): F[ServableResponse] = {
        val req = PredictRequest(
          modelSpec = Some(ModelSpec(
            name = servable.modelVersion.name,
            version = servable.modelVersion.version.some,
            signatureName = servable.modelVersion.predict.signatureName
          )),
          inputs = data
        )
        for {
          start <- clock.monotonic(TimeUnit.MILLISECONDS)
          res <- stub.predict(req).attempt
          end <- clock.monotonic(TimeUnit.MILLISECONDS)
        } yield ServableResponse(
          data = res.map(_.outputs),
          metadata = ResponseMetadata(
            latency = end - start
          )
        )
      }

      override def close: F[Unit] = stub.close()
    }
  }
}
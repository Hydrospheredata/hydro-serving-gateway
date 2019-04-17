package io.hydrosphere.serving.gateway.service.application

import cats.effect.Sync
import io.grpc.{Deadline, ManagedChannelBuilder}
import io.hydrosphere.serving.gateway.api.grpc.PredictDownstream.WrappedStub
import io.hydrosphere.serving.gateway.persistence.servable.StoredServable
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc

import scala.concurrent.duration.Duration

trait Predictor[F[_]] {
  def predict(data: PredictRequest): F[PredictResponse]
}

object Predictor {
  def mkServablePredictor[F: Sync](servable: StoredServable, deadline: Duration) = Sync[F].delay {
      val builder = ManagedChannelBuilder
        .forAddress(servable.host, servable.port)

      builder.usePlaintext()
      builder.enableRetry()

      val channel = builder.build()
      val stub = PredictionServiceGrpc.stub(channel)
      new WrappedStub(stub, deadline, channel, servable.modelVersion.id)
    }
}
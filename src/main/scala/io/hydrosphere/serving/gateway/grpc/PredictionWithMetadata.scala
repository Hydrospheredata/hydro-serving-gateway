package io.hydrosphere.serving.gateway.grpc

import io.hydrosphere.serving.tensorflow.api.predict.PredictResponse

case class PredictionWithMetadata(
  response: PredictResponse,
  modelVersionId: Option[String],
  latency: Option[String]
)

object PredictionWithMetadata {
  type PredictionOrException = Either[Throwable, PredictionWithMetadata]
}
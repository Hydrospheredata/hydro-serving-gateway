package io.hydrosphere.serving.gateway.execution.servable

import io.hydrosphere.serving.tensorflow.tensor.TensorProto

final case class ServableResponse(
  data: Either[Throwable, Map[String, TensorProto]],
  metadata: ResponseMetadata
)

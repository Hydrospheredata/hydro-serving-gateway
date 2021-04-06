package io.hydrosphere.serving.gateway.execution

import io.hydrosphere.serving.gateway.execution.servable.Predictor
import io.hydrosphere.serving.gateway.persistence.StoredServable
import io.hydrosphere.serving.proto.contract.tensor.Tensor

object Types {
  type MessageData = Map[String, Tensor]
  type PredictorCtor[F[_]] = StoredServable => F[Predictor[F]]
}
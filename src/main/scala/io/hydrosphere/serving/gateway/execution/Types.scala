package io.hydrosphere.serving.gateway.execution

import io.hydrosphere.serving.gateway.execution.servable.Predictor
import io.hydrosphere.serving.gateway.integrations.reqstore.ReqStore
import io.hydrosphere.serving.gateway.persistence.StoredServable
import io.hydrosphere.serving.monitoring.api.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest
import io.hydrosphere.serving.tensorflow.tensor.TensorProto

object Types {
  type MessageData = Map[String, TensorProto]
  type ServableCtor[F[_]] = StoredServable => F[Predictor[F]]
  type ServingReqStore[F[_]] = ReqStore[F, (PredictRequest, ResponseOrError)]
}
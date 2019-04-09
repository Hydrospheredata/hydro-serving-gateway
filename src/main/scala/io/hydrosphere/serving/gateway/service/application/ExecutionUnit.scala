package io.hydrosphere.serving.gateway.service.application

import io.hydrosphere.serving.gateway.persistence.application.PredictDownstream

case class ExecutionUnit(
  client: PredictDownstream,
  meta: ExecutionMeta
)

package io.hydrosphere.serving.gateway.execution.application

import io.hydrosphere.serving.gateway.execution.servable.ServableResponse
import io.hydrosphere.serving.gateway.persistence.StoredServable

case class AssociatedResponse(
  resp: ServableResponse,
  servable: StoredServable,
)

package io.hydrosphere.serving.gateway.execution.servable

import io.hydrosphere.serving.gateway.execution.Types.MessageData

final case class ServableRequest(
  data: MessageData,
  requestId: String,
)

final case class ServableResponse(
  data: Either[Throwable, MessageData],
  latency: Double
)
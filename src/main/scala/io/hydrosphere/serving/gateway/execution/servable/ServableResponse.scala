package io.hydrosphere.serving.gateway.execution.servable

import io.hydrosphere.serving.gateway.execution.Types.MessageData
import io.hydrosphere.serving.monitoring.metadata.TraceData

final case class ServableRequest(
  data: MessageData,
  requestId: String,
  replayTrace: Option[TraceData] = None,
)

final case class ServableResponse(
  data: Either[Throwable, MessageData],
  latency: Double
)
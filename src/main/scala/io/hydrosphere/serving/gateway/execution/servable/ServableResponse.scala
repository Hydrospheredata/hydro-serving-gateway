package io.hydrosphere.serving.gateway.execution.servable

import io.hydrosphere.serving.gateway.execution.Types.MessageData
import io.hydrosphere.serving.monitoring.metadata.TraceData

final case class ServableRequest(
  data: MessageData,
  replayTrace: Option[TraceData] = None,
  requestId: Option[String] = None
)

final case class ServableResponse(
  data: Either[Throwable, MessageData],
  latency: Double
)
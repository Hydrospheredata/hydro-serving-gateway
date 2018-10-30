package io.hydrosphere.serving.gateway.service.application

case class RequestTracingInfo(
  xRequestId: String,
  xB3requestId: Option[String] = None,
  xB3SpanId: Option[String] = None
)

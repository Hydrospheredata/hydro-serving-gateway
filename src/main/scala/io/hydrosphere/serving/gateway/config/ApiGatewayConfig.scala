package io.hydrosphere.serving.gateway.config

import scala.concurrent.duration.FiniteDuration

final case class ApiGatewayConfig(
  host: String,
  grpcPort: Int,
  httpPort: Int,
  reconnectTimeout: FiniteDuration
)

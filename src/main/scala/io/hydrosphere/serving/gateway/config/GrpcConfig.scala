package io.hydrosphere.serving.gateway.config

import scala.concurrent.duration.Duration

final case class GrpcConfig(
  port: Int,
  deadline: Duration,
  maxMessageSize: Int = 4 * 1024 * 1024,
)

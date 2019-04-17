package io.hydrosphere.serving.gateway.config

final case class ReqStoreConfig(
  enabled: Boolean,
  host: String,
  port: Int,
  schema: String
)

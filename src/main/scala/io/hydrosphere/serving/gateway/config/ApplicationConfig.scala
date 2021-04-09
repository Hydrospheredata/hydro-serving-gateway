package io.hydrosphere.serving.gateway.config

final case class ApplicationConfig(
  grpc: GrpcConfig,
  http: HttpConfig,
  shadowingOn: Boolean,
  apiGateway: ApiGatewayConfig,
  streaming: Option[KafkaConfig] = None
)

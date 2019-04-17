package io.hydrosphere.serving.gateway.config

final case class ApplicationConfig(
  grpc: GrpcConfig,
  http: HttpConfig,
  shadowingOn: Boolean,
  reqstore: ReqStoreConfig,
  apiGateway: ApiGatewayConfig,
  streaming: KafkaConfig
)

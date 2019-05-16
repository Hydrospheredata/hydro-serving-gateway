package io.hydrosphere.serving.gateway.config

final case class KafkaConfig(
  advertisedHost: String = "kafka",
  advertisedPort: Int = 9092,
  shadowTopic: String = "shadow_topic"
)

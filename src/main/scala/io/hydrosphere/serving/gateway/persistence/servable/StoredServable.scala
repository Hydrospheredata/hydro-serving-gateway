package io.hydrosphere.serving.gateway.persistence.servable

import io.hydrosphere.serving.manager.grpc.entities.ModelVersion

case class StoredServable(
  host: String,
  port: Int,
  weight: Int,
  modelVersion: ModelVersion
)

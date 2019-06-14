package io.hydrosphere.serving.gateway.persistence

import io.hydrosphere.serving.manager.grpc.entities.Servable

case class StoredServable(
  name: String,
  host: String,
  port: Int,
  weight: Int,
  modelVersion: StoredModelVersion
)


object StoredServable {
  def parse(servable: Servable): Either[String, StoredServable] = {
    for {
      mv <- servable.modelVersion.toRight("Servable doesn't contain model version info")
      parsedMv <- StoredModelVersion.parse(mv)
    } yield
      StoredServable(
        name = servable.name,
        host = servable.host,
        port = servable.port,
        weight = servable.weight,
        modelVersion = parsedMv
      )
  }
}
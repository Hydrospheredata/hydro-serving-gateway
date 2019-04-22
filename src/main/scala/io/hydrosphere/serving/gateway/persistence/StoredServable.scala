package io.hydrosphere.serving.gateway.persistence

import io.hydrosphere.serving.manager.grpc.entities.Servable

case class StoredServable(
  host: String,
  port: Int,
  weight: Int,
  modelVersion: StoredModelVersion
) {
  def name: String = modelVersion.name + ":" + modelVersion.version // TODO check in manager if this is true
}

object StoredServable {
  def parse(servable: Servable): Either[String, StoredServable] = {
    for {
      mv <- servable.modelVersion.toRight("Servable doesn't contain model version info")
      parsedMv <- StoredModelVersion.parse(mv)
    } yield
      StoredServable(
        host = servable.host,
        port = servable.port,
        weight = servable.weight,
        modelVersion = parsedMv
      )
  }
}
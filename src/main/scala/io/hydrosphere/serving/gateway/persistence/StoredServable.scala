package io.hydrosphere.serving.gateway.persistence

import io.hydrosphere.serving.proto.manager.entities.Servable

case class StoredServable(
  name: String,
  host: String,
  port: Int,
  weight: Int,
  modelVersion: StoredModelVersion,
) {
  def isReconnectNeeded(that: StoredServable): Boolean = {
    name == that.name &&
      (host != that.host || port != that.port)
  }
}


object StoredServable {
  def parse(servable: Servable): Either[String, StoredServable] = {
    for {
      mv <- servable.modelVersion.toRight("Servable doesn't contain model version info")
      _ <- Either.cond(servable.port != 0, "ok", "Servable is not ready") // goddamit protobuf why don't you have optional types
      parsedMv <- StoredModelVersion.parse(mv)
    } yield
      StoredServable(
        name = servable.name,
        host = servable.host,
        port = servable.port,
        weight = servable.weight,
        modelVersion = parsedMv,
      )
  }
}
package io.hydrosphere.serving.gateway.persistence

import io.hydrosphere.serving.proto.contract.signature.ModelSignature
import io.hydrosphere.serving.proto.manager.entities.ModelVersion

case class StoredModelVersion(
  id: Long,
  version: Long,
  name: String,
  signature: ModelSignature,
  status: String
) {
  def fullName = s"$name-$version"
}

object StoredModelVersion {
  def parse(mv: ModelVersion): Either[String, StoredModelVersion] = {
    for {
      signature <- mv.signature.toRight("Version without signature")
    } yield StoredModelVersion(
      id = mv.id,
      name = mv.name,
      version = mv.version,
      signature = signature,
      status = mv.status.name
    )
  }
}












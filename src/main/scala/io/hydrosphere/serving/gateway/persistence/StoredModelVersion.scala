package io.hydrosphere.serving.gateway.persistence

import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.grpc.entities.ModelVersion

case class StoredModelVersion(
  id: Long,
  version: Long,
  name: String,
  predict: ModelSignature,
  status: String
) {
  def fullName = s"$name-$version"
}

object StoredModelVersion {
  def parse(mv: ModelVersion): Either[String, StoredModelVersion] = {
    for {
      model <- mv.model.toRight("Version without model")
      predict <- mv.contract.flatMap(_.predict).toRight("Version without predict signature")
    } yield StoredModelVersion(
      id = mv.id,
      name = model.name,
      version = mv.version,
      predict = predict,
      status = mv.status
    )
  }
}












package io.hydrosphere.serving.gateway.persistence

import cats.Traverse
import cats.data.NonEmptyList
import cats.implicits._
import io.circe.generic.JsonCodec
import io.hydrosphere.serving.proto.contract.signature.ModelSignature
import io.hydrosphere.serving.proto.manager.entities.Stage

case class StoredStage(
  id: String,
  servables: NonEmptyList[StoredServable],
  signature: ModelSignature
)

object StoredStage {
  def parse(stage: Stage): Either[String, StoredStage] = {
    for {
      stageSig <- stage.signature.toRight("Stage must have a signature")
      servables <- NonEmptyList.fromList(stage.servable.toList).toRight("Stage must have servables")
      parsedServables <- Traverse[NonEmptyList].traverse(servables)(StoredServable.parse)
    } yield StoredStage(stage.stageId, parsedServables, stageSig)
  }
}
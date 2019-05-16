package io.hydrosphere.serving.gateway.persistence

import cats.Traverse
import cats.implicits._
import cats.data.NonEmptyList
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.grpc.entities.ServingApp

import scala.util.Try

object StoredApplication {
  def parse(app: ServingApp): Either[String, StoredApplication] = {
    val out = for {
      stages <- NonEmptyList.fromList(app.pipeline.toList)
        .toRight("Application must have stages. None provided.")
      parsedStages <- Traverse[NonEmptyList].traverse(stages)(StoredStage.parse)
      contract <- app.contract.flatMap(_.predict).toRight("Application doesn't have a predict signature")
      id <- Try(app.id.toLong).toEither.left.map(_.getMessage)
    } yield StoredApplication(
      id = id,
      name = app.name,
      namespace = None,
      signature = contract,
      stages = parsedStages
    )

    out.leftMap(s => s"Invalid app: ${app.id}, ${app.name}. $s")
  }
}

case class StoredApplication(
  id: Long,
  name: String,
  namespace: Option[String],
  signature: ModelSignature,
  stages: NonEmptyList[StoredStage]
)
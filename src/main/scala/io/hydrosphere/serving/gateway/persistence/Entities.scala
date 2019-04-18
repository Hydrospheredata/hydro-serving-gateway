package io.hydrosphere.serving.gateway.persistence

import cats.Traverse
import cats.data.NonEmptyList
import cats.implicits._
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.grpc.entities.{ModelVersion, Servable, ServingApp, Stage}

import scala.util.Try

case class StoredModelVersion(
  id: Long,
  version: Long,
  name: String,
  predict: ModelSignature,
  status: String
)

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

case class StoredApplication(
  id: Long,
  name: String,
  namespace: Option[String],
  signature: ModelSignature,
  stages: NonEmptyList[StoredStage]
)

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
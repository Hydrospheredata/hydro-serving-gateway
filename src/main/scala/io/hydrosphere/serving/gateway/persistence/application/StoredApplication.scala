package io.hydrosphere.serving.gateway.persistence.application

import akka.actor.ActorSystem
import cats.data.NonEmptyList
import cats.implicits._
import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.gateway.api.grpc.PredictDownstream
import io.hydrosphere.serving.gateway.persistence.servable.StoredServable
import io.hydrosphere.serving.manager.grpc.entities.{Servable, ServingApp, Stage}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration


case class StoredStage(
  id: String,
  services: NonEmptyList[StoredServable],
  signature: ModelSignature
)

case class StoredApplication(
  id: String,
  name: String,
  namespace: Option[String],
  contract: ModelContract,
  stages: Seq[StoredStage]
) {

  def close(): Future[Unit] = {
    val closes = stages.map(_.client.close())
    Future.sequence(closes).map(_ => ())
  }
}

object StoredApplication {

  def create(app: ServingApp, deadline: Duration, sys: ActorSystem): Either[String, StoredApplication] = {
    val out = for {
      contract <- app.contract.toValid("Contract field is required").toEither
      stages   <- app.pipeline.toList.traverse(s => createStage(s, deadline, sys))
    } yield {
      StoredApplication(
        id = app.id,
        name = app.name,
        namespace = None,
        contract = contract,
        stages = stages
      )
    }
    out.leftMap(s => s"Invalid app: ${app.id}, ${app.name}. $s")
  }

  private def createStage(stage: Stage, deadline: Duration, sys: ActorSystem): Either[String, StoredStage] = {
    def toService(servable: Servable): Option[StoredServable] = {
      servable.modelVersion.map { mv =>
        StoredServable(
          host = servable.host,
          port = servable.port,
          weight = servable.weight,
          modelVersion = mv
        )
      }
    }
    
    (stage.signature, NonEmptyList.fromList(stage.servable.toList)) match {
      case (Some(sig), Some(srvbls)) =>
        srvbls.traverse(toService) match {
          case Some(services) =>
            val downstream = PredictDownstream.create(services, deadline, sys)
            StoredStage(stage.stageId, services, sig, downstream).asRight
          case None =>
            s"Invalid stage ${stage.stageId}. No model information in servables $srvbls".asLeft
        }
      case (x, y)=>
        s"Invalid stage ${stage.stageId}. Signature field: $x. Servables: $y".asLeft
    }
  }
  
}
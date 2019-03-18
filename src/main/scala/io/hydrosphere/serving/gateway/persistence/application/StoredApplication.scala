package io.hydrosphere.serving.gateway.persistence.application

import akka.actor.ActorSystem
import cats.data.NonEmptyList
import cats.implicits._
import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.discovery.serving.{Servable, ServingApp, Stage}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

case class StoredService(
  host: String,
  port: Int,
  weight: Int,
)

case class StoredStage(
  id: String,
  services: NonEmptyList[StoredService],
  signature: ModelSignature,
  client: PredictDownstream
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
    def toService(servable: Servable): StoredService = {
      StoredService(
        host = servable.host,
        port = servable.port,
        weight = servable.weight
      )
    }
    
    
    (stage.signature, NonEmptyList.fromList(stage.servable.toList)) match {
      case (Some(sig), Some(srvbls)) =>
        val downstream = PredictDownstream.create(srvbls, deadline, sys)
        StoredStage(stage.stageId, srvbls.map(toService), sig, downstream).asRight
      case (x, y)=>
        s"Invalid stage ${stage.stageId}. Signature field: $x. Servables: $y".asLeft
    }
  }
  
}
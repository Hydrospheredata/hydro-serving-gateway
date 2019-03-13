package io.hydrosphere.serving.gateway.persistence.application

import cats._
import cats.implicits._

import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.discovery.serving.{Servable, ServingApp, Stage}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc.PredictionServiceStub

case class StoredService(
  host: String,
  port: Int,
  weight: Int,
  channel: ManagedChannel,
  client: PredictionServiceStub
)

case class StoredStage(
  id: String,
  services: Seq[StoredService],
  signature: ModelSignature
)

case class StoredApplication(
  id: String,
  name: String,
  namespace: Option[String],
  contract: ModelContract,
  stages: Seq[StoredStage]
) {
  
  def close(): Unit = {
    stages.flatMap(_.services).map(_.channel.shutdown())
  }
}

object StoredApplication {
  
  def fromProto(app: ServingApp): Either[String, StoredApplication] = {
    for {
      contract <- app.contract.toValid("Contract field is required").toEither
      stages   <- app.pipeline.toList.traverse(stageFromProto)
    } yield {
      StoredApplication(
        id = app.id,
        name = app.name,
        namespace = None,
        contract = contract,
        stages = stages
      )
    }
  }
  
  private def stageFromProto(stage: Stage): Either[String, StoredStage] = {
    def toService(servable: Servable): StoredService = {
      val builder = ManagedChannelBuilder.forAddress(servable.host, servable.port)
      builder.enableRetry()
  
      val chanell = builder.build()
      val stub = PredictionServiceGrpc.stub(chanell)
  
      StoredService(
        host = servable.host,
        port = servable.port,
        weight = servable.weight,
        channel = chanell,
        client = stub
      )
    }
    
    stage.signature match {
      case Some(sig) =>
        StoredStage(stage.stageId, stage.servable.map(toService), sig).asRight
      case None =>
        s"Signature field is missing: ${stage.stageId}".asLeft
    }
  }
  
}
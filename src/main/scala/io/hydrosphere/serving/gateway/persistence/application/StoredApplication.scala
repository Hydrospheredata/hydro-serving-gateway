package io.hydrosphere.serving.gateway.persistence.application

import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.grpc.applications.Application

import scala.util.Try

case class StoredStage(
  id: String,
  services: Seq[StoredService],
  signature: Option[ModelSignature],
)

case class StoredService(
  modelVersionId: Long,
  weight: Int
)


case class StoredExecutionGraph(
  stages: Seq[StoredStage]
)


case class StoredApplication(
  id: Long,
  name: String,
  namespace: Option[String],
  contract: ModelContract,
  executionGraph: StoredExecutionGraph
)

object StoredApplication {
  // NOTE warnings missing fields?
  def fromProto(app: Application) = Try {
    val executionGraph = app.executionGraph.map { execGraph =>
      StoredExecutionGraph(
        stages = execGraph.stages.map { stage =>
          StoredStage(
            id = stage.stageId,
            signature = stage.signature,
            services = stage.services.map { service =>
              StoredService(
                modelVersionId = service.modelVersion.map(_.id).getOrElse(throw new IllegalArgumentException("ExecutionService cannot be without modelVersionId")),
                weight = service.weight
              )
            }.toList
          )
        }.toList
      )
    }

    val namespace = if (app.namespace.isEmpty) {
      None
    } else {
      Some(app.namespace)
    }

    StoredApplication(
      id = app.id,
      name = app.name,
      namespace = namespace,
      contract = app.contract.getOrElse(throw new IllegalArgumentException("Application cannot be without modelContract")),
      executionGraph = executionGraph.getOrElse(throw new IllegalArgumentException("Application cannot be without executionGraph"))
    )
  }
}
package io.hydrosphere.serving.gateway.persistence.application

import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.grpc.applications.Application
import io.hydrosphere.serving.monitoring.data_profile_types.DataProfileType

case class StoredStage(
  id: String,
  services: Seq[StoredService],
  signature: Option[ModelSignature],
  dataProfileFields: Map[String, DataProfileType]
)

case class StoredService(
  runtimeId: Long,
  modelVersionId: Option[Long],
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
  def fromProto(app: Application) = {
    val executionGraph = app.executionGraph.map { execGraph =>
      StoredExecutionGraph(
        stages = execGraph.stages.map { stage =>
          StoredStage(
            id = stage.stageId,
            signature = stage.signature,
            dataProfileFields = stage.dataTypes,
            services = stage.services.map { service =>
              StoredService(
                runtimeId = service.runtime.map(_.id).getOrElse(0L),
                modelVersionId = service.modelVersion.map(_.id),
                weight = service.weight
              )}.toList
          )}.toList
      )}

    val namespace = if (app.namespace.isEmpty) {
      None
    } else {
      Some(app.namespace)
    }

    StoredApplication(
      id = app.id,
      name = app.name,
      namespace = namespace,
      contract = app.contract.getOrElse(ModelContract.defaultInstance),
      executionGraph = executionGraph.getOrElse(StoredExecutionGraph(stages = Seq.empty))
    )
  }
}
package io.hydrosphere.serving.gateway.service

import java.util.concurrent.locks.ReentrantReadWriteLock

import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.grpc.applications.Application
import io.hydrosphere.serving.model.api.{HResult, Result}
import Result.Implicits._
import io.hydrosphere.serving.model.api.Result.ClientError
import io.hydrosphere.serving.monitoring.data_profile_types.DataProfileType

import scala.collection.mutable

case class GWService(
  runtimeId: Long,
  modelVersionId: Option[Long],
  weight: Int
)

case class GWStage(
  id: String,
  services: Seq[GWService],
  signature: Option[ModelSignature],
  dataProfileFields: Map[String, DataProfileType]
)

case class GWExecutionGraph(
  stages: Seq[GWStage]
)

case class GWApplication(
  id: Long,
  name: String,
  namespace: Option[String],
  contract: ModelContract,
  executionGraph: GWExecutionGraph
)

object GWApplication {
  // NOTE warnings missing fields?
  def fromProto(app: Application) = {
    val executionGraph = app.executionGraph.map { execGraph =>
      GWExecutionGraph(
        stages = execGraph.stages.map { stage =>
          GWStage(
            id = stage.stageId,
            signature = stage.signature,
            dataProfileFields = stage.dataTypes,
            services = stage.services.map { service =>
              GWService(
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

    GWApplication(
      id = app.id,
      name = app.name,
      namespace = namespace,
      contract = app.contract.getOrElse(ModelContract.defaultInstance),
      executionGraph = executionGraph.getOrElse(GWExecutionGraph(stages = Seq.empty))
    )
  }
}

trait ApplicationStorage {
  def get(name: String): HResult[GWApplication]
  def get(id: Long): HResult[GWApplication]

  def version: String

  def update(apps: Seq[Application], version: String): Unit
}

class ApplicationStorageImpl extends ApplicationStorage {
  private[this] val applicationsById = mutable.Map[Long, GWApplication]()
  private[this] val applicationsByName = mutable.Map[String, GWApplication]()
  private[this] val rwLock = new ReentrantReadWriteLock()
  private[this] var currentVersion = "0"

  def version: String = {
    val lock = rwLock.readLock()
    try {
      lock.lock()
      currentVersion
    } finally {
      lock.unlock()
    }
  }

  def get(name: String): HResult[GWApplication] = {
    val lock = rwLock.readLock()
    try {
      lock.lock()
      applicationsByName.get(name).toHResult(ClientError(s"Can't find application with name $name"))
    } finally {
      lock.unlock()
    }
  }

  def get(id: Long): HResult[GWApplication] = {
    val lock = rwLock.readLock()
    try {
      lock.lock()
      applicationsById.get(id).toHResult(ClientError(s"Can't find application with id $id"))
    } finally {
      lock.unlock()
    }
  }

  private def updateStorageInIds(apps: Seq[GWApplication]): Unit = {
    val toRemove = applicationsById.keySet -- apps.map(_.id).toSet
    toRemove.foreach(applicationsById.remove)
    apps.foreach { app =>
      applicationsById.put(app.id, app)
    }
  }

  private def updateStorageInNames(apps: Seq[GWApplication]): Unit = {
    val toRemove = applicationsByName.keySet -- apps.map(_.name).toSet
    toRemove.foreach(applicationsByName.remove)
    apps.foreach { app =>
      applicationsByName.put(app.name, app)
    }
  }

  def update(apps: Seq[Application], version: String): Unit = {
    val mapped = apps.map(GWApplication.fromProto)

    val lock = rwLock.writeLock()
    try {
      lock.lock()
      updateStorageInNames(mapped)
      updateStorageInIds(mapped)
      currentVersion = version
    } finally {
      lock.unlock()
    }
    Unit
  }
}
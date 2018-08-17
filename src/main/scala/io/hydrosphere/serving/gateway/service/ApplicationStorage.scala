package io.hydrosphere.serving.gateway.service

import java.util.concurrent.locks.ReentrantReadWriteLock

import io.hydrosphere.serving.contract.model_contract.ModelContract
import io.hydrosphere.serving.contract.model_signature.ModelSignature
import io.hydrosphere.serving.manager.grpc.applications.Application
import io.hydrosphere.serving.model.api.{HResult, Result}
import io.hydrosphere.serving.monitoring.data_profile_types.DataProfileType

import scala.collection.mutable
import scala.concurrent.Future

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
  namespace: String,
  contract: ModelContract,
  executionGraph: GWExecutionGraph,
)

class ApplicationStorage {
  private[this] val applicationsById = mutable.Map[Long, GWApplication]()
  private[this] val applicationsByName = mutable.Map[String, GWApplication]()
  private[this] val rwLock = new ReentrantReadWriteLock()
  private[this] var currentVersion = "0"

  def getVersion(): String ={
    val lock = rwLock.readLock()
    try{
      lock.lock()
      currentVersion
    } finally {
      lock.unlock()
    }
  }

  def getApplication(name: String): HResult[GWApplication] = {
    val lock = rwLock.readLock()
    try{
      lock.lock()
      applicationsByName.get(name) match {
        case Some(app)=> Result.ok(app)
        case _=>Result.clientError(s"Can't find application with name $name")
      }
    } finally {
      lock.unlock()
    }
  }

  def getApplication(id: Long): HResult[GWApplication] = {
    val lock = rwLock.readLock()
    try{
      lock.lock()
      applicationsById.get(id) match {
        case Some(app)=> Result.ok(app)
        case _=>Result.clientError(s"Can't find application with id $id")
      }
    } finally {
      lock.unlock()
    }
  }

  private def updateStorageInIds(apps: Seq[GWApplication]): Unit = {
    val toRemove = applicationsById.keySet -- apps.map(_.id).toSet
    toRemove.foreach(id => applicationsById.remove(id))
    apps.foreach(app => {
      applicationsById.put(app.id, app)
    })
  }

  private def updateStorageInNames(apps: Seq[GWApplication]): Unit = {
    val toRemove = applicationsByName.keySet -- apps.map(_.name).toSet
    toRemove.foreach(id => applicationsByName.remove(id))
    apps.foreach(app => {
      applicationsByName.put(app.name, app)
    })
  }

  def updateStorage(apps: Seq[Application], version:String): Future[Unit] = Future.successful({
    val mapped = apps.map(app => {
      GWApplication(
        id = app.id,
        name = app.name,
        namespace = app.name,
        contract = app.contract.getOrElse(ModelContract()),
        executionGraph = app.executionGraph.map(execGraph => {
          GWExecutionGraph(
            stages = execGraph.stages.map(stage => {
              GWStage(
                id = stage.stageId,
                signature = stage.signature,
                dataProfileFields = stage.dataTypes,
                services = stage.services.map(service => {
                  GWService(
                    runtimeId = service.runtime.map(_.id).getOrElse(0L),
                    modelVersionId = service.modelVersion.map(_.id),
                    weight = service.weight
                  )
                })
              )
            })
          )
        }).getOrElse(GWExecutionGraph(stages = Seq()))
      )

      val lock = rwLock.writeLock()
      try{
        lock.lock()
        updateStorageInNames(mapped)
        updateStorageInIds(mapped)
        currentVersion=version
      } finally {
        lock.unlock()
      }
      Unit
    })
  })

}
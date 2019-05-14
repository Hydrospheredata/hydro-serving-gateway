package io.hydrosphere.serving.gateway.persistence.application

import cats.effect.Async
import cats.implicits._
import io.hydrosphere.serving.gateway.execution.Types.ServableCtor
import io.hydrosphere.serving.gateway.execution.application.{MonitorExec, ResponseSelector}
import io.hydrosphere.serving.gateway.execution.servable.ServableExec
import io.hydrosphere.serving.gateway.persistence.StoredApplication
import io.hydrosphere.serving.gateway.util.ReadWriteLock

trait ApplicationStorage[F[_]] {
  def getByName(name: String): F[Option[StoredApplication]]
  def getById(id: Long): F[Option[StoredApplication]]
  def getExecutor(name: String): F[Option[ServableExec[F]]]

  def listAll: F[List[StoredApplication]]

  def addApps(apps: List[StoredApplication]): F[Unit]
  def removeApps(ids: List[Long]): F[List[StoredApplication]]
}

object ApplicationStorage {
  def makeInMemory[F[_]](
    servableCtor: ServableCtor[F],
    shadow: MonitorExec[F],
    selector: ResponseSelector[F]
  )(implicit F: Async[F]) = {
    for {
      lock <- ReadWriteLock.reentrant
    } yield new ApplicationInMemoryStorage[F](lock, servableCtor, shadow, selector)
  }
}
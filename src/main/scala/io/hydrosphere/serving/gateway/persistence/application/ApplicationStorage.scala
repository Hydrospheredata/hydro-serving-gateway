package io.hydrosphere.serving.gateway.persistence.application

import cats.effect.{Async, Clock}
import cats.implicits._
import io.hydrosphere.serving.gateway.execution.Types.ServableCtor
import io.hydrosphere.serving.gateway.execution.application.{MonitorExec, ResponseSelector}
import io.hydrosphere.serving.gateway.execution.servable.ServableExec
import io.hydrosphere.serving.gateway.persistence.StoredApplication
import io.hydrosphere.serving.gateway.util.{InstantClock, ReadWriteLock}

trait ApplicationStorage[F[_]] {
  def getByName(name: String): F[Option[StoredApplication]]
  def getById(id: Long): F[Option[StoredApplication]]
  def getExecutor(name: String): F[Option[ServableExec[F]]]

  def listAll: F[List[StoredApplication]]

  def addApps(apps: Seq[StoredApplication]): F[Unit]
  def removeApps(ids: Seq[Long]): F[List[StoredApplication]]
}

object ApplicationStorage {
  def makeInMemory[F[_]](
    servableCtor: ServableCtor[F],
    shadow: MonitorExec[F],
    selector: ResponseSelector[F]
  )(implicit F: Async[F], clock: InstantClock[F]) = {
    for {
      lock <- ReadWriteLock.reentrant
    } yield new ApplicationInMemoryStorage[F](lock, servableCtor, shadow, selector)
  }
}
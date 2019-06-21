package io.hydrosphere.serving.gateway.persistence.application

import cats.effect.{Async, Concurrent}
import cats.implicits._
import io.hydrosphere.serving.gateway.execution.Types.PredictorCtor
import io.hydrosphere.serving.gateway.execution.application.{MonitoringClient, ResponseSelector}
import io.hydrosphere.serving.gateway.execution.servable.Predictor
import io.hydrosphere.serving.gateway.persistence.StoredApplication
import io.hydrosphere.serving.gateway.util.ReadWriteLock

trait ApplicationStorage[F[_]] {
  def getByName(name: String): F[Option[StoredApplication]]
  def getById(id: Long): F[Option[StoredApplication]]
  def getExecutor(name: String): F[Option[Predictor[F]]]

  def listAll: F[List[StoredApplication]]

  def addApps(apps: List[StoredApplication]): F[Unit]
  def removeApps(ids: Seq[String]): F[List[StoredApplication]]
}

object ApplicationStorage {
  def makeInMemory[F[_]](
    servableCtor: PredictorCtor[F],
    shadow: MonitoringClient[F],
    selector: ResponseSelector[F]
  )(implicit F: Concurrent[F]) = {
    for {
      lock <- ReadWriteLock.reentrant
    } yield new ApplicationInMemoryStorage[F](lock, servableCtor, shadow, selector)
  }
}
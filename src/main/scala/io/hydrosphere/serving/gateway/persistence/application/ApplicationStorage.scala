package io.hydrosphere.serving.gateway.persistence.application

import cats.effect.Sync

trait ApplicationStorage[F[_]] {
  def getByName(name: String): F[Option[StoredApplication]]
  def getById(id: String): F[Option[StoredApplication]]

  def listAll: F[Seq[StoredApplication]]

  def addApps(apps: Seq[StoredApplication]): F[Unit]
  def removeApps(ids: Seq[String]): F[List[StoredApplication]]
}

object ApplicationStorage {
  def makeInMemory[F[_]](implicit F: Sync[F]) = F.delay {
    new ApplicationInMemoryStorage[F]
  }
}
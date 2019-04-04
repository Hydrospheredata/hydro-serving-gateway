package io.hydrosphere.serving.gateway.persistence.application

trait ApplicationStorage[F[_]] {
  def getByName(name: String): F[Option[StoredApplication]]
  def getById(id: String): F[Option[StoredApplication]]

  def listAll: F[Seq[StoredApplication]]

  def addApps(apps: Seq[StoredApplication]): F[Unit]
  def removeApps(ids: Seq[String]): F[Unit]
}
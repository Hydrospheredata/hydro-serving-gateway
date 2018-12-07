package io.hydrosphere.serving.gateway.persistence.application

trait ApplicationStorage[F[_]] {
  def get(name: String): F[Option[StoredApplication]]

  def get(id: Long): F[Option[StoredApplication]]

  def version: F[String]

  def listAll: F[Seq[StoredApplication]]

  def update(apps: Seq[StoredApplication], version: String): F[String]
}
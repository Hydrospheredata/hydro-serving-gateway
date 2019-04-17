package io.hydrosphere.serving.gateway.persistence.servable

trait ServableStorage[F[_]] {
  def get(name: String): F[Option[StoredServable]]
  def getByModelVersion(model: String, version: Long): F[Option[StoredServable]]

  def list: F[Seq[StoredServable]]

  def add(apps: Seq[StoredServable]): F[Unit]
  def remove(ids: Seq[StoredServable]): F[Unit]
}
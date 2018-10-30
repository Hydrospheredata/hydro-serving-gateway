package io.hydrosphere.serving.gateway.persistence.application

import io.hydrosphere.serving.manager.grpc.applications.Application

trait ApplicationStorage[F[_]] {
  def get(name: String): F[Option[StoredApplication]]
  def get(id: Long): F[Option[StoredApplication]]
  def version: F[String]
  def listAll: F[Seq[StoredApplication]]
  def update(apps: Seq[Application], version: String): F[String]
}

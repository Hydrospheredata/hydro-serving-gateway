package io.hydrosphere.serving.gateway.persistence.servable

import cats.effect.{Clock, Sync}
import cats.implicits._
import io.hydrosphere.serving.gateway.persistence.StoredServable
import io.hydrosphere.serving.gateway.service.application.PredictionClientFactory
import io.hydrosphere.serving.gateway.util.ReadWriteLock

trait ServableStorage[F[_]] {
  def get(name: String): F[Option[StoredServable]]
  def getByModelVersion(model: String, version: Long): F[Option[StoredServable]]

  def list: F[List[StoredServable]]

  def add(apps: Seq[StoredServable]): F[Unit]
  def remove(ids: Seq[String]): F[Unit]
}

object ServableStorage {
  def makeInMemory[F[_]](clientCtor: PredictionClientFactory[F])(implicit F: Sync[F], clock: Clock[F]) = {
    for {
      lock <- ReadWriteLock.reentrant
    } yield new ServableInMemoryStorage[F](lock, clientCtor)
  }
}
package io.hydrosphere.serving.gateway.persistence.servable

import java.util.concurrent.locks.{Lock, ReentrantReadWriteLock}

import cats.effect.Sync
import io.hydrosphere.serving.manager.grpc.entities.Servable

import scala.collection.mutable

class ServableInMemoryStorage[F[_]: Sync] extends ServableStorage[F] {
  private[this] val servables = mutable.Map[String, StoredServable]()
  private[this] val rwLock = new ReentrantReadWriteLock()
  
  private def usingLock[A](l: Lock)(f: => A): F[A] = Sync[F].delay {
      try {
        l.lock()
        f
      } finally {
        l.unlock()
      }
    }

  
  private def usingReadLock[A](f: => A): F[A] = usingLock(rwLock.readLock())(f)
  private def usingWriteLock[A](f: => A): F[A] = usingLock(rwLock.writeLock())(f)
  
  override def list: F[Seq[StoredServable]] =
    usingReadLock(servables.values.toSeq)

  override def get(name: String): F[Option[StoredServable]] =
    usingReadLock(servables.get(name))

  override def getByModelVersion(name: String, version: Long): F[Option[StoredServable]] =
    usingReadLock(servables.get(s"$name:$version"))

  override def add(apps: Seq[StoredServable]): F[Unit] = {

  }

  override def remove(ids: Seq[String]): F[Unit] = {

  }
}
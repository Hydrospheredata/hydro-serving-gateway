package io.hydrosphere.serving.gateway.persistence.application

import java.util.concurrent.locks.ReentrantReadWriteLock

import cats.Applicative
import io.hydrosphere.serving.manager.grpc.applications.Application

import scala.collection.mutable


class ApplicationInMemoryStorage[F[_]: Applicative] extends ApplicationStorage[F] {
  private[this] val applicationsById = mutable.Map[Long, StoredApplication]()
  private[this] val applicationsByName = mutable.Map[String, StoredApplication]()
  private[this] val rwLock = new ReentrantReadWriteLock()
  private[this] var currentVersion = "0"

  def version: F[String] = {
    val lock = rwLock.readLock()
    try {
      lock.lock()
      Applicative[F].pure(currentVersion)
    } finally {
      lock.unlock()
    }
  }

  def listAll :F[Seq[StoredApplication]] = {
    val lock = rwLock.readLock()
    try {
      lock.lock()
      Applicative[F].pure(applicationsById.values.toList)
    } finally {
      lock.unlock()
    }
  }

  def get(name: String): F[Option[StoredApplication]] = {
    val lock = rwLock.readLock()
    try {
      lock.lock()
      Applicative[F].pure(applicationsByName.get(name))
    } finally {
      lock.unlock()
    }
  }

  def get(id: Long): F[Option[StoredApplication]] = {
    val lock = rwLock.readLock()
    try {
      lock.lock()
      Applicative[F].pure(applicationsById.get(id))
    } finally {
      lock.unlock()
    }
  }

  private def updateStorageInIds(apps: Seq[StoredApplication]): Unit = {
    val toRemove = applicationsById.keySet -- apps.map(_.id).toSet
    toRemove.foreach(applicationsById.remove)
    apps.foreach { app =>
      applicationsById.put(app.id, app)
    }
  }

  private def updateStorageInNames(apps: Seq[StoredApplication]): Unit = {
    val toRemove = applicationsByName.keySet -- apps.map(_.name).toSet
    toRemove.foreach(applicationsByName.remove)
    apps.foreach { app =>
      applicationsByName.put(app.name, app)
    }
  }

  def update(apps: Seq[Application], version: String): F[String] = {
    val mapped = apps.map(StoredApplication.fromProto)

    val lock = rwLock.writeLock()
    try {
      lock.lock()
      updateStorageInNames(mapped)
      updateStorageInIds(mapped)
      currentVersion = version
    } finally {
      lock.unlock()
    }
    Applicative[F].pure(version)
  }
}
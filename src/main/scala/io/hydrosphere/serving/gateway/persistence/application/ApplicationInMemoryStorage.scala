package io.hydrosphere.serving.gateway.persistence.application

import java.util.concurrent.locks.ReentrantReadWriteLock

import cats.Applicative
import cats.syntax.applicative._

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
       currentVersion.pure
    } finally {
      lock.unlock()
    }
  }

  def listAll: F[Seq[StoredApplication]] = {
    val lock = rwLock.readLock()
    try {
      lock.lock()
      applicationsById.values.toSeq.pure
    } finally {
      lock.unlock()
    }
  }

  def get(name: String): F[Option[StoredApplication]] = {
    val lock = rwLock.readLock()
    try {
      lock.lock()
      applicationsByName.get(name).pure
    } finally {
      lock.unlock()
    }
  }

  def get(id: Long): F[Option[StoredApplication]] = {
    val lock = rwLock.readLock()
    try {
      lock.lock()
      applicationsById.get(id).pure
    } finally {
      lock.unlock()
    }
  }

  def update(apps: Seq[StoredApplication], version: String): F[String] = {
    val lock = rwLock.writeLock()
    try {
      lock.lock()
      updateStorageInNames(apps)
      updateStorageInIds(apps)
      currentVersion = version
    } finally {
      lock.unlock()
    }
    version.pure
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

}
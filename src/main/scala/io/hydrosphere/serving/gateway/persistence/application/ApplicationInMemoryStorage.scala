package io.hydrosphere.serving.gateway.persistence.application

import java.util.concurrent.locks.{Lock, ReentrantReadWriteLock}

import cats.effect.Sync

import scala.collection.mutable

class ApplicationInMemoryStorage[F[_]: Sync] extends ApplicationStorage[F] {
  private[this] val applicationsById = mutable.Map[String, StoredApplication]()
  private[this] val applicationsByName = mutable.Map[String, StoredApplication]()
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
  
  override def listAll: F[Seq[StoredApplication]] =
    usingReadLock(applicationsById.values.toSeq)

  override def getByName(name: String): F[Option[StoredApplication]] =
    usingReadLock(applicationsByName.get(name))

  override def getById(id: String): F[Option[StoredApplication]] =
    usingReadLock(applicationsById.get(id))

  override def addApps(apps: Seq[StoredApplication]): F[Unit] = {
    usingWriteLock {
      apps.foreach { app =>
        val prev = applicationsById.put(app.id, app)
        applicationsByName.put(app.name, app)
        prev.foreach(_.close())
      }
    }
  }

  override def removeApps(ids: Seq[String]) = {
    usingWriteLock {
      ids.flatMap { id =>
        applicationsById.get(id) match {
          case Some(app) =>
            applicationsById.remove(id)
            applicationsByName.remove(app.name)
            app.close()
            List(app)
          case None => Nil
        }
      }
    }
  }
  
}
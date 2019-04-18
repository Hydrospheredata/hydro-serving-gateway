package io.hydrosphere.serving.gateway.persistence.application

import cats.effect.{Async, Clock}
import cats.implicits._
import io.hydrosphere.serving.gateway.persistence.StoredApplication
import io.hydrosphere.serving.gateway.service.application.Types.ServableCtor
import io.hydrosphere.serving.gateway.service.application._
import io.hydrosphere.serving.gateway.util.ReadWriteLock

import scala.collection.mutable

class ApplicationInMemoryStorage[F[_]](
  rwLock: ReadWriteLock[F],
  channelFactory: ServableCtor[F],
  shadow: MonitorExec[F],
  selector: ResponseSelector[F]
)(implicit F: Async[F], clock: Clock[F]) extends ApplicationStorage[F] {
  private[this] val applicationsById = mutable.Map.empty[Long, StoredApplication]
  private[this] val applicationsByName = mutable.Map.empty[String, StoredApplication]
  private[this] val executors = mutable.Map.empty[String, ServableExec[F]]

  override def listAll: F[List[StoredApplication]] =
    rwLock.read.use(_ => F.pure(applicationsById.values.toList))

  override def getByName(name: String): F[Option[StoredApplication]] =
    rwLock.read.use(_ => F.delay(applicationsByName.get(name)))

  override def getById(id: Long): F[Option[StoredApplication]] =
    rwLock.read.use(_ => F.delay(applicationsById.get(id)))

  override def addApps(apps: Seq[StoredApplication]): F[Unit] = {
    rwLock.write.use { _ =>
      F.delay {
        apps.foreach { app =>
          for {
            stages <- app.stages.traverse { x =>
              StageExec.withShadow(app, x, channelFactory, shadow, selector)
            }
            pipelineExec = ApplicationExecutor.pipelineExecutor(stages)
          } yield {
            applicationsById += app.id -> app
            applicationsByName += app.name -> app
            executors += app.name -> pipelineExec
          }
        }
      }
    }
  }

  override def removeApps(ids: Seq[Long]): F[List[StoredApplication]] = {
    rwLock.write.use { _ =>
      F.delay {
        ids.flatMap { id =>
          applicationsById.get(id) match {
            case Some(app) =>
              applicationsById.remove(id)
              applicationsByName.remove(app.name)
              List(app)
            case None => Nil
          }
        }
      }
    }
  }
}
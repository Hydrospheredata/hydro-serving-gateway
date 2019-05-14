package io.hydrosphere.serving.gateway.persistence.application

import cats.effect.Concurrent
import cats.implicits._
import io.hydrosphere.serving.gateway.execution.Types.ServableCtor
import io.hydrosphere.serving.gateway.execution.application._
import io.hydrosphere.serving.gateway.execution.servable.Predictor
import io.hydrosphere.serving.gateway.persistence.StoredApplication
import io.hydrosphere.serving.gateway.util.ReadWriteLock

import scala.collection.mutable

class ApplicationInMemoryStorage[F[_]](
  rwLock: ReadWriteLock[F],
  channelFactory: ServableCtor[F],
  shadow: MonitoringClient[F],
  selector: ResponseSelector[F]
)(implicit F: Concurrent[F]) extends ApplicationStorage[F] {
  private[this] val applicationsById = mutable.Map.empty[Long, StoredApplication]
  private[this] val applicationsByName = mutable.Map.empty[String, StoredApplication]
  private[this] val executors = mutable.Map.empty[String, Predictor[F]]

  override def listAll: F[List[StoredApplication]] =
    rwLock.read.use(_ => F.pure(applicationsById.values.toList))

  override def getByName(name: String): F[Option[StoredApplication]] =
    rwLock.read.use(_ => F.delay(applicationsByName.get(name)))

  override def getById(id: Long): F[Option[StoredApplication]] =
    rwLock.read.use(_ => F.delay(applicationsById.get(id)))

  override def addApps(apps: List[StoredApplication]): F[Unit] = {
    rwLock.write.use { _ =>
      apps.traverse { app =>
        for {
          stages <- app.stages.traverse { x =>
            StagePredictor.withShadow(app, x, channelFactory, shadow, selector)
          }
          pipelineExec = ApplicationExecutor.pipelineExecutor(stages)
        } yield {
          applicationsById += app.id -> app
          applicationsByName += app.name -> app
          executors += app.name -> pipelineExec
        }
      }.as(F.unit)
    }
  }

  override def removeApps(ids: List[Long]): F[List[StoredApplication]] = {
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

  override def getExecutor(name: String): F[Option[Predictor[F]]] = {
    rwLock.read.use { _ =>
      F.delay {
        executors.get(name)
      }
    }
  }
}
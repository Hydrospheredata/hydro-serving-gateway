package io.hydrosphere.serving.gateway.persistence.servable

import cats.effect.{Clock, Sync}
import cats.implicits._
import io.hydrosphere.serving.gateway.execution.grpc.PredictionClient
import io.hydrosphere.serving.gateway.persistence.StoredServable
import io.hydrosphere.serving.gateway.execution.servable.{CloseableExec, ServableExec}
import io.hydrosphere.serving.gateway.util.ReadWriteLock

import scala.collection.mutable

/**
  * We don't have Application and Servable statuses implemented yet.
  * Thus, we implemented a hack that track number of servable allocations to try to track actual infrastructure.
  * @param lock read write lock implementation to synchronize concurrent access to the inner state
  * @tparam F Effectful type
  */
class ServableInMemoryStorage[F[_]: Sync](
  lock: ReadWriteLock[F],
  clientCtor: PredictionClient.Factory[F]
)(implicit clock: Clock[F]) extends ServableStorage[F] {
  private val F = Sync[F]
  private[this] val servableState = mutable.Map.empty[String, StoredServable]
  private[this] val servableCounter = mutable.Map.empty[String, Long]
  private[this] val servableExecutor = mutable.Map.empty[String, CloseableExec[F]]

  override def list: F[List[StoredServable]] =
    lock.read.use(_ => F.pure(servableState.values.toList))

  override def get(name: String): F[Option[StoredServable]] =
    lock.read.use(_ => F.delay(servableState.get(name)))

  override def getByModelVersion(name: String, version: Long): F[Option[StoredServable]] =
    lock.read.use(_ => F.delay(servableState.get(s"$name:$version")))

  override def add(servables: Seq[StoredServable]): F[Unit] = {
    lock.write.use { _ =>
      servables.toList.traverse[F, Unit] { s =>
        servableState.get(s.name) match {
          case Some(_) =>
            for {
              oldCounter <- F.delay(servableCounter.getOrElseUpdate(s.name, 0))
              _ <- F.delay(servableCounter.update(s.name, oldCounter + 1))
            } yield ()
          case None =>
            for {
              exec <- ServableExec.forServable(s, clientCtor)
              _ <- F.delay(servableState += s.name -> s)
              _ <- F.delay(servableCounter += s.name -> 1)
              _ <- F.delay(servableExecutor += s.name -> exec)
            } yield ()
        }
      }.as(F.unit)
    }
  }

  override def remove(ids: Seq[String]): F[Unit] = {
    lock.write.use { _ =>
      F.delay {
        ids.foreach { name =>
          servableCounter.getOrElseUpdate(name, 0) match {
            case 0 =>
              for {
                _ <- F.delay(servableState -= name)
                _ <- F.delay(servableCounter -= name)
                _ <- servableExecutor(name).close
              } yield ()
            case x => F.delay(servableCounter.update(name, x - 1)).as(F.unit)
          }
        }
      }
    }
  }

  def getExecutor(servable: StoredServable): F[ServableExec[F]] = {
    lock.read.use { _ =>
      F.delay(servableExecutor(servable.name))
    }
  }

  override def getExecutor(modelName: String, modelVersion: Long): F[Option[ServableExec[F]]] = {
    lock.read.use{ _ =>
      F.delay(servableExecutor.get(s"$modelName:$modelVersion"))
    }
  }
}
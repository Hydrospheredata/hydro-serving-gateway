package io.hydrosphere.serving.gateway.persistence.servable

import cats.data.OptionT
import cats.effect.{Clock, Sync}
import cats.implicits._
import io.hydrosphere.serving.gateway.execution.application.MonitoringClient
import io.hydrosphere.serving.gateway.execution.grpc.PredictionClient
import io.hydrosphere.serving.gateway.persistence.StoredServable
import io.hydrosphere.serving.gateway.execution.servable.{CloseablePredictor, Predictor}
import io.hydrosphere.serving.gateway.util.ReadWriteLock

import scala.collection.mutable

/**
  * @param lock read write lock implementation to synchronize concurrent access to the inner state
  * @tparam F Effectful type
  */
class ServableInMemoryStorage[F[_]](
  lock: ReadWriteLock[F],
  clientCtor: PredictionClient.Factory[F],
  shadow: MonitoringClient[F]
)(
  implicit F: Sync[F],
  clock: Clock[F]
) extends ServableStorage[F] {
  private[this] val servableState = mutable.Map.empty[String, StoredServable]
  private[this] val servableExecutors = mutable.Map.empty[String, CloseablePredictor[F]]
  private[this] val monitorableExecutors = mutable.Map.empty[String, Predictor[F]]

  override def list: F[List[StoredServable]] =
    lock.read.use(_ => F.pure(servableState.values.toList))

  override def get(name: String): F[Option[StoredServable]] =
    lock.read.use(_ => F.delay(servableState.get(name)))

  override def add(servables: Seq[StoredServable]): F[Unit] = {
    lock.write.use { _ =>
      servables.toList.traverse[F, Unit] { s =>
        servableState.get(s.name) match {
          case Some(stored) =>
            for {
              _ <- F.delay(servableState.update(s.name, s))
              
              _ <- F.defer {
                if (stored.isReconnectNeeded(s)) {
                  for {
                    _ <- OptionT(F.delay(servableExecutors.get(s.name)))
                      .flatMap(x => OptionT.liftF(x.close)).value

                    exec <- Predictor.forServable(s, clientCtor)
                    shadowed = Predictor.withShadow(s, exec, shadow, None)

                    _ <- F.delay(servableExecutors.update(s.name, exec))
                    _ <- F.delay(monitorableExecutors.update(s.name, shadowed))
                  } yield ()
                } else F.unit
              }
            } yield ()
          case None =>
            for {
              exec <- Predictor.forServable(s, clientCtor)
              shadowed = Predictor.withShadow(s, exec, shadow, None)
              _ <- F.delay(servableState += s.name -> s)
              _ <- F.delay(servableExecutors += s.name -> exec)
              _ <- F.delay(monitorableExecutors += s.name -> shadowed)
            } yield ()
        }
      }.void
    }
  }

  override def remove(ids: Seq[String]): F[Unit] = {
    lock.write.use { _ =>
      ids.toList.traverse { name =>
        servableState.get(name) match {
          case Some(_) =>
            val flow = for {
              _ <- F.delay(servableState -= name)
              _ <- servableExecutors(name).close
              _ <- F.delay(servableExecutors -= name)
              _ <- F.delay(monitorableExecutors -= name)
            } yield ()
            flow.attempt.void
          case None => F.unit
        }
      }.void
    }
  }

  def getExecutor(servable: StoredServable): F[Predictor[F]] = {
    lock.read.use { _ =>
      F.delay(servableExecutors(servable.name))
    }
  }

  override def getShadowedExecutor(servableName: String): F[Option[Predictor[F]]] = {
    lock.read.use { _ =>
      F.delay(monitorableExecutors.get(servableName))
    }
  }

  override def getExecutor(servableName: String): F[Option[Predictor[F]]] = {
    lock.read.use { _ =>
      F.delay(servableExecutors.get(servableName))
    }
  }
}
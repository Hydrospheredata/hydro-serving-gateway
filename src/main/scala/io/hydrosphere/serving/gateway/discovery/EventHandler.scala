package io.hydrosphere.serving.gateway.discovery

import cats.Monad
import cats.data.Chain
import cats.implicits._
import io.hydrosphere.serving.discovery.serving.{ApplicationDiscoveryEvent, ServableDiscoveryEvent}
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import io.hydrosphere.serving.gateway.persistence.servable.ServableStorage
import io.hydrosphere.serving.gateway.persistence.{StoredApplication, StoredServable}
import org.apache.logging.log4j.scala.Logging

trait EventHandler[F[_], E] {
  type EventType = E
  def handle(event: E): F[Unit]
}

object EventHandler {

trait ServableEventHandler[F[_]] extends EventHandler[F, ServableDiscoveryEvent]

object ServableEventHandler extends Logging {
  def default[F[_] : Monad](servableStorage: ServableStorage[F]): ServableEventHandler[F] = {
    event: ServableDiscoveryEvent => {
      logger.debug(s"Servable stream update: $event")
      val converted = event.added.map(s => StoredServable.parse(s))
      val (addedServables, parsingErrors) =
        converted.foldLeft((Chain.empty[StoredServable], Chain.empty[String])) {
          case ((_valid, _invalid), Left(e)) => (_valid, _invalid.prepend(e))
          case ((_valid, _invalid), Right(v)) => (_valid.prepend(v), _invalid)
        }
      parsingErrors.map { msg =>
        logger.error(s"Received invalid servable. $msg".slice(0, 512))
      }
      addedServables.map { servable =>
        logger.info(s"Received servable: $servable".slice(0, 512))
      }
      val removed = event.removedIdx.toList
      logger.info(s"Removed servables: $removed")
      for {
        _ <- servableStorage.add(addedServables.toList)
        _ <- servableStorage.remove(removed)
      } yield ()
    }
  }
}

trait ApplicationEventHandler[F[_]] extends EventHandler[F, ApplicationDiscoveryEvent]

object ApplicationEventHandler extends Logging {
  def default[F[_] : Monad](applicationStorage: ApplicationStorage[F], servableStorage: ServableStorage[F]): ApplicationEventHandler[F] = {
    event: ApplicationDiscoveryEvent => {
      logger.debug(s"Application stream update: $event")
      val converted = event.added.map(app => StoredApplication.parse(app))
      val (addedApplications, parsingErrors) =
        converted.foldLeft((Chain.empty[StoredApplication], Chain.empty[String]))({
          case ((_valid, _invalid), Left(e)) => (_valid, _invalid.prepend(e))
          case ((_valid, _invalid), Right(v)) => (_valid.prepend(v), _invalid)
        })

      parsingErrors.map { msg =>
        logger.error(s"Received invalid application. $msg".slice(0, 512))
      }

      addedApplications.map { app =>
        logger.info(s"Received application: $app".slice(0, 512))
      }

      val servables = addedApplications.toList.flatMap(_.stages.toList.flatMap(_.servables.toList))
      for {
        _ <- servableStorage.add(servables)
        _ <- applicationStorage.addApps(addedApplications.toList)
        _ <- applicationStorage.removeApps(event.removedIds)
      } yield ()
    }
  }
}
}
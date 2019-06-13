package io.hydrosphere.serving.gateway.discovery.application

import akka.actor.{Actor, ActorLogging, Props, Timers}
import cats.data.Chain
import cats.effect.Effect
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import io.hydrosphere.serving.discovery.serving.{ApplicationDiscoveryEvent, ServableDiscoveryEvent, ServingDiscoveryGrpc}
import io.hydrosphere.serving.gateway.config.ApiGatewayConfig
import io.hydrosphere.serving.gateway.discovery.application.DiscoveryWatcher._
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import io.hydrosphere.serving.gateway.persistence.servable.ServableStorage
import io.hydrosphere.serving.gateway.persistence.{StoredApplication, StoredServable}

import scala.concurrent.duration.Duration
import scala.util.Try

class DiscoveryWatcher[F[_]](
  apiGatewayConf: ApiGatewayConfig,
  clientDeadline: Duration,
  applicationStorage: ApplicationStorage[F],
  servableStorage: ServableStorage[F]
)(implicit F: Effect[F]) extends Actor with Timers with ActorLogging {
  val stub: ServingDiscoveryGrpc.ServingDiscoveryStub = {
    val builder = ManagedChannelBuilder
      .forAddress(apiGatewayConf.host, apiGatewayConf.grpcPort)

    builder.enableRetry()
    builder.usePlaintext()

    val manager = builder.build()

    log.debug(s"Created a channel: ${manager.authority()}")
    ServingDiscoveryGrpc.stub(manager)
  }

  override def aroundPreStart(): Unit = self ! Connect

  override def receive: Receive = disconnected

  def disconnected: Receive = {
    case Connect =>
      Try(connect()) match {
        case scala.util.Success((app, serv)) => context become listening(app, serv)
        case scala.util.Failure(e) =>
          log.error(e, s"Can't setup discovery connection")
          timers.startSingleTimer("connect", Connect, apiGatewayConf.reconnectTimeout)
      }
  }


  def listening(appResponse: StreamObserver[Empty], servableResponse: StreamObserver[Empty]): Receive = {
    case resp: ApplicationDiscoveryEvent => handleAppEvent(resp)

    case ev: ServableDiscoveryEvent => handleServableEvent(ev)

    case ConnectionFailed(maybeE) =>
      maybeE match {
        case Some(e) => log.debug(s"Discovery stream was failed with error: $e")
        case None => log.warning("Discovery stream was closed")
      }
      timers.startSingleTimer("connect", Connect, apiGatewayConf.reconnectTimeout)
      context become disconnected
    case x =>
      log.debug(s"Unknown message: $x")
  }

  def handleServableEvent(ev: ServableDiscoveryEvent): Unit = {
    log.debug(s"Servable stream update: $ev")
    val converted = ev.added.map(s => StoredServable.parse(s))
    val (addedServables, parsingErrors) =
      converted.foldLeft((Chain.empty[StoredServable], Chain.empty[String])) {
        case ((_valid, _invalid), Left(e)) => (_valid, _invalid.prepend(e))
        case ((_valid, _invalid), Right(v)) => (_valid.prepend(v), _invalid)
      }
    parsingErrors.map { msg =>
      log.error(s"Received invalid servable. $msg".slice(0, 512))
    }
    addedServables.map { servable =>
      log.info(s"Received servable: $servable".slice(0, 512))
    }
    val removed = ev.removedIdx.toList
      log.info(s"Removed servables: $removed")
    val upd = for {
      _ <- servableStorage.add(addedServables.toList)
      _ <- servableStorage.remove(removed)
    } yield ()
    upd.toIO.unsafeRunSync()
  }

  private def handleAppEvent(resp: ApplicationDiscoveryEvent): Unit = {
    log.debug(s"Application stream update: $resp")
    val converted = resp.added.map(app => StoredApplication.parse(app))
    val (addedApplications, parsingErrors) =
      converted.foldLeft((Chain.empty[StoredApplication], Chain.empty[String]))({
        case ((_valid, _invalid), Left(e)) => (_valid, _invalid.prepend(e))
        case ((_valid, _invalid), Right(v)) => (_valid.prepend(v), _invalid)
      })

    parsingErrors.map { msg =>
      log.error(s"Received invalid application. $msg".slice(0, 512))
    }

    addedApplications.map { app =>
      log.info(s"Received application: $app".slice(0, 512))
    }

    val upd = for {
      parsedRemovedIds <- resp.removedIds.toList.traverse(x => F.fromTry(Try(x.toLong)))
      _ <- applicationStorage.addApps(addedApplications.toList)
      _ <- applicationStorage.removeApps(parsedRemovedIds)
    } yield ()
    upd.toIO.unsafeRunSync()
  }

  private def connect() = {
    val appObserver = new StreamObserver[ApplicationDiscoveryEvent] {
      override def onError(e: Throwable): Unit = {
        self ! ConnectionFailed(Option(e))
      }

      override def onCompleted(): Unit = {
        self ! ConnectionFailed(None)
      }

      override def onNext(value: ApplicationDiscoveryEvent): Unit = {
        self ! value
      }
    }
    val sObserver = new StreamObserver[ServableDiscoveryEvent] {
      override def onNext(value: ServableDiscoveryEvent): Unit = {
        self ! value
      }

      override def onError(t: Throwable): Unit = {
        self ! ConnectionFailed(Option(t))
      }

      override def onCompleted(): Unit = {
        self ! ConnectionFailed(None)
      }
    }
    val apps = stub.watchApplications(appObserver)
    val serv = stub.watchServables(sObserver)
    (apps, serv)
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.error(reason, "Restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse("<no message>"))
  }
}

object DiscoveryWatcher {

  case class ConnectionFailed(err: Option[Throwable])

  case object Connect

  def props[F[_] : Effect](
    apiGatewayConf: ApiGatewayConfig,
    clientDeadline: Duration,
    applicationStorage: ApplicationStorage[F],
    servableStorage: ServableStorage[F]
  ): Props = Props(new DiscoveryWatcher(apiGatewayConf, clientDeadline, applicationStorage, servableStorage))
}
package io.hydrosphere.serving.gateway.discovery

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, Timers}
import cats.effect.Effect
import cats.implicits._
import cats.effect.implicits._
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import io.hydrosphere.serving.discovery.serving.{ApplicationDiscoveryEvent, ServableDiscoveryEvent, ServingDiscoveryGrpc}
import io.hydrosphere.serving.gateway.config.ApiGatewayConfig
import io.hydrosphere.serving.gateway.discovery.DiscoveryWatcher._
import io.hydrosphere.serving.gateway.discovery.EventHandler.{ApplicationEventHandler, ServableEventHandler}

import scala.concurrent.duration.Duration
import scala.util.Try


class DiscoveryWatcher[F[_]](
  apiGatewayConf: ApiGatewayConfig,
  clientDeadline: Duration,
  appHandler: ApplicationEventHandler[F],
  servableHandler: ServableEventHandler[F]
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
    case ev: ApplicationDiscoveryEvent =>
      appHandler.handle(ev).toIO.unsafeRunSync()

    case ev: ServableDiscoveryEvent =>
      servableHandler.handle(ev).toIO.unsafeRunSync()

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

  private def connect() = {
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
    val serv = stub.watchServables(sObserver)
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

    val apps = stub.watchApplications(appObserver)
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
    applicationEventHandler: ApplicationEventHandler[F],
    servableEventHandler: ServableEventHandler[F]
  ): Props = Props(new DiscoveryWatcher(apiGatewayConf, clientDeadline, applicationEventHandler, servableEventHandler))

  def make[F[_]](
    apiGatewayConfig: ApiGatewayConfig,
    clientDeadline: Duration,
    applicationEventHandler: ApplicationEventHandler[F],
    servableEventHandler: ServableEventHandler[F]
  )(
    implicit F: Effect[F],
    actorSystem: ActorSystem
  ) = {
    F.delay(actorSystem.actorOf(props(apiGatewayConfig, clientDeadline, applicationEventHandler, servableEventHandler)))
  }
}
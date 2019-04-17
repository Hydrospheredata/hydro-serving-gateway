package io.hydrosphere.serving.gateway.discovery.application

import akka.actor.{Actor, ActorLogging, Props, Timers}
import cats.effect.Effect
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.effect.syntax.effect._
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import io.hydrosphere.serving.discovery.serving.{ServingDiscoveryGrpc, WatchResp}
import io.hydrosphere.serving.gateway.config.ApiGatewayConfig
import io.hydrosphere.serving.gateway.discovery.application.DiscoveryWatcher._
import io.hydrosphere.serving.gateway.persistence.application.{ApplicationStorage, StoredApplication}
import io.hydrosphere.serving.gateway.persistence.servable.ServableStorage

import scala.concurrent.duration.Duration
import scala.util.Try

class DiscoveryWatcher[F[_]: Effect](
  apiGatewayConf: ApiGatewayConfig,
  clientDeadline: Duration,
  applicationStorage: ApplicationStorage[F],
  servableStorage: ServableStorage[F]
) extends Actor with Timers with ActorLogging {

  import context._

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
        case scala.util.Success(v) => context become listening(v)
        case scala.util.Failure(e) =>
          log.error(e, s"Can't setup discovery connection")
          timers.startSingleTimer("connect", Connect, apiGatewayConf.reconnectTimeout)
      }
  }

  def listening(response: StreamObserver[Empty]): Receive = {
    case resp: WatchResp => handleResp(resp)

    case ConnectionFailed(maybeE) =>
      maybeE match {
        case Some(e) => log.error(e, "Discovery stream was failed with error")
        case None => log.warning("Discovery stream was closed")
      }
      timers.startSingleTimer("connect", Connect, apiGatewayConf.reconnectTimeout)
      context become disconnected
  }

  private def handleResp(resp: WatchResp): Unit = {
    log.info(s"Discovery stream update: $resp")
    val converted = resp.added.map(app => StoredApplication.create(app, clientDeadline, system))
    val (valid, invalid) =
      converted.foldLeft((List.empty[StoredApplication], List.empty[String]))({
        case ((_valid, _invalid), Left(e)) => (_valid, e :: _invalid)
        case ((_valid, _invalid), Right(v)) => (v :: _valid, _invalid)
      })

    invalid.foreach(msg => {
      log.error(s"Received invalid application. $msg")
    })

    valid.foreach(app => {
      log.info(s"Received application: $app")
    })

    val servables = valid.flatMap { s =>
      s.stages.toList.flatMap(_.services.toList)
    }

    val upd = for {
      _ <- applicationStorage.addApps(valid)
      _ <- servableStorage.add(servables)
      removed <- applicationStorage.removeApps(resp.removedIds)
      removedServables = removed.flatMap(s => s.stages.toList.flatMap(_.services.toList))
      _ <- servableStorage.remove(removedServables)
    } yield ()
    upd.toIO.unsafeRunSync()
  }

  private def connect(): StreamObserver[Empty] = {
    val observer = new StreamObserver[WatchResp] {
      override def onError(e: Throwable): Unit = {
        self ! ConnectionFailed(Some(e))
      }

      override def onCompleted(): Unit = {
        self ! ConnectionFailed(None)
      }

      override def onNext(resp: WatchResp): Unit = {
        self ! resp
      }
    }
    stub.watch(observer)
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.error(reason, "Restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
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
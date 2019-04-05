package io.hydrosphere.serving.gateway.discovery.application

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Timers}
import cats.effect.IO
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import io.hydrosphere.serving.discovery.serving.{ServingDiscoveryGrpc, WatchResp}
import io.hydrosphere.serving.gateway.config.ApiGatewayConfig
import io.hydrosphere.serving.gateway.discovery.application.DiscoveryWatcher.{Connect, ConnectionFailed}
import io.hydrosphere.serving.gateway.persistence.application.{ApplicationStorage, StoredApplication}
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration._
import scala.util.Try

class DiscoveryService(
  apiGatewayConf: ApiGatewayConfig,
  clientDeadline: Duration,
  applicationStorage: ApplicationStorage[IO]
)(implicit actorSystem: ActorSystem) extends Logging {

  val actor: ActorRef = actorSystem.actorOf(DiscoveryWatcher.props(apiGatewayConf, clientDeadline, applicationStorage))
}

class DiscoveryWatcher(
  apiGatewayConf: ApiGatewayConfig,
  clientDeadline: Duration,
  applicationStorage: ApplicationStorage[IO]
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
  
    val upd = for {
      _ <- applicationStorage.addApps(valid)
      _ <- applicationStorage.removeApps(resp.removedIds)
    } yield ()
    upd.unsafeRunSync()
    
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

  def props(
    apiGatewayConf: ApiGatewayConfig,
    clientDeadline: Duration,
    applicationStorage: ApplicationStorage[IO]
  ): Props = Props(new DiscoveryWatcher(apiGatewayConf, clientDeadline, applicationStorage))
}
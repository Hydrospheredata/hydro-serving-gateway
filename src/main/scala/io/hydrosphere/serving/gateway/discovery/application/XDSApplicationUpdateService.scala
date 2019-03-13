package io.hydrosphere.serving.gateway.discovery.application

import java.time.Instant

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import cats.effect.Effect
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver
import io.hydrosphere.serving.discovery.serving.{ServingDiscoveryGrpc, WatchReq, WatchResp}
import io.hydrosphere.serving.gateway.config.ManagerConfig
import io.hydrosphere.serving.gateway.discovery.application.XDSActor.{GetUpdates, Tick}
import io.hydrosphere.serving.gateway.persistence.application.{ApplicationStorage, StoredApplication}
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration._

class XDSApplicationUpdateService[F[_]: Effect](
  applicationStorage: ApplicationStorage[F],
  sidecarConfig: ManagerConfig
)(implicit actorSystem: ActorSystem) extends Logging {

  val actor = actorSystem.actorOf(XDSActor.props(sidecarConfig, applicationStorage))

  def getUpdates(): Unit = {
    actor ! GetUpdates
  }
}

class XDSActor[F[_]: Effect](
  sidecarConfig: ManagerConfig,
  applicationStorage: ApplicationStorage[F]
) extends Actor with ActorLogging {

  import context._

  private val tickTimer = context.system.scheduler.schedule(10.seconds, 15.seconds, self, Tick)
  private var lastResponse = Instant.MIN

  val observer = new StreamObserver[WatchResp] {
    override def onError(t: Throwable): Unit = {
      log.error("Application stream exception: {}", t.getMessage)
      context become connecting
    }

    override def onCompleted(): Unit = {
      log.debug("Application stream closed")
      context become connecting
    }

    override def onNext(resp: WatchResp): Unit = {
      log.debug(s"Discovery stream update: $resp")

      lastResponse = Instant.now()
      val converted = resp.added.map(StoredApplication.fromProto)
      val (valid, invalid) =
        converted.foldLeft((List.empty[StoredApplication], List.empty[String]))({
          case ((valid, invalid), Left(e)) => (valid, e :: invalid)
          case ((valid, invalid), Right(v)) => (v :: valid, invalid)
        })
      
      invalid.foreach(msg => {
        log.error(s"Received invalid application. $msg")
      })
      
      valid.foreach(app => {
        log.error(s"Received application: $app")
      })
      applicationStorage.addApps(valid)
      applicationStorage.removeApps(resp.removedIds)
    }
  }

  final override def receive: Receive = connecting

  def connecting: Receive = {
    case Tick =>
      log.debug(s"Connecting to stream")
      try {
        val builder = ManagedChannelBuilder
          .forAddress(sidecarConfig.host, sidecarConfig.port)
        builder.enableRetry()
        builder.usePlaintext()

        val manager = builder.build()

        log.debug(s"Created a channel: ${manager.authority()}")

        val xDSClient = ServingDiscoveryGrpc.stub(manager)
        val result = xDSClient.watch(observer)
        context become listening(result)
      } catch {
        case err: Exception => log.warning(s"Can't connect: $err")
      }
  }

  def listening(response: StreamObserver[WatchReq]): Receive = {
    case GetUpdates => response.onNext(WatchReq())
    case Tick =>
      val now = Instant.now()
      val lastRequiredResponse = now.minusSeconds(sidecarConfig.xdsSilentRestartSeconds)
      if (lastResponse.isBefore(lastRequiredResponse)) {
        log.warning(s"Didn't get XDS responses in ${sidecarConfig.xdsSilentRestartSeconds} seconds. Possible stream error. Restarting...")
        context.become(connecting)
      }
      response.onNext(WatchReq())
  }

  override def preStart() = {
    log.debug("Starting")
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.error(reason, "Restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
  }


}

object XDSActor {

  case object Tick

  case object GetUpdates

  def props[F[_]: Effect](
    managerConfig: ManagerConfig,
    applicationStorage: ApplicationStorage[F]
  ) = Props(new XDSActor[F](managerConfig, applicationStorage))
}
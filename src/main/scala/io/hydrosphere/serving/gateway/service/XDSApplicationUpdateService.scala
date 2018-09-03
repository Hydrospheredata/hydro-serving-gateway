package io.hydrosphere.serving.gateway.service

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorSystem, Cancellable, Props}
import envoy.api.v2.core.Node
import envoy.api.v2.{DiscoveryRequest, DiscoveryResponse}
import envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc
import envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryService
import io.grpc.netty.NettyChannelBuilder
import io.grpc.{Channel, ClientInterceptors, ManagedChannelBuilder}
import io.grpc.stub.StreamObserver
import io.hydrosphere.serving.gateway.config.Inject.appConfig
import io.hydrosphere.serving.gateway.config.SidecarConfig
import io.hydrosphere.serving.gateway.service.XDSActor.{GetUpdates, Tick}
import io.hydrosphere.serving.grpc.{AuthorityReplacerInterceptor, Headers}
import io.hydrosphere.serving.manager.grpc.applications.{ExecutionGraph, Application => ProtoApplication}
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration._
import scala.util.Try

class XDSApplicationUpdateService(
  applicationStorage: ApplicationStorage,
  sidecarConfig: SidecarConfig
)(implicit actorSystem: ActorSystem) extends Logging {

  val actor = actorSystem.actorOf(XDSActor.props(sidecarConfig, applicationStorage))

  val typeUrl = "type.googleapis.com/io.hydrosphere.serving.manager.grpc.applications.Application"

  def getUpdates(): Unit = {
    actor ! GetUpdates
  }
}

class XDSActor(
  sidecarConfig: SidecarConfig,
  applicationStorage: ApplicationStorage
) extends Actor with ActorLogging {

  import context._

  val observer = new StreamObserver[DiscoveryResponse] {
    override def onError(t: Throwable): Unit = {
      log.error("Application stream exception: {}", t.getMessage)
      context become connecting
    }

    override def onCompleted(): Unit = {
      log.info("Application stream closed")
      context become connecting
    }

    override def onNext(value: DiscoveryResponse): Unit = {
      log.info(s"Discovery stream update: $value")

      val applications = value.resources.flatMap { resource =>
        Try(resource.unpack(ProtoApplication)).toOption
      }
      log.info(s"Discovered applications:\n${prettyPrintApps(applications)}")
      applicationStorage.update(applications, value.versionInfo)
    }
  }

  val typeUrl = "type.googleapis.com/io.hydrosphere.serving.manager.grpc.applications.Application"

  private val timer = context.system.scheduler.schedule(3.seconds, 10.seconds, self, Tick)

  final override def receive: Receive = connecting

  def connecting: Receive = {
    case Tick =>
      log.info(s"Connecting to stream")
      try {
        val builder = ManagedChannelBuilder
          .forAddress(appConfig.sidecar.host, appConfig.sidecar.port)

        builder.keepAliveTime(10, TimeUnit.SECONDS)
        builder.keepAliveWithoutCalls(true)
        builder.enableRetry()
        builder.usePlaintext()

        val sidecarChannel: Channel = ClientInterceptors
          .intercept(builder.build, new AuthorityReplacerInterceptor +: Headers.interceptors: _*)

        val xDSClient = AggregatedDiscoveryServiceGrpc.stub(sidecarChannel)
        val result = xDSClient.streamAggregatedResources(observer)
        update(result)
        context become listening(result)
      } catch {
        case err: Exception => log.warning(s"Can't connect: $err")
      }
  }

  def listening(response: StreamObserver[DiscoveryRequest]): Receive = {
    case GetUpdates => update(response)
    case Tick =>
      update(response) // FIXME keepalive?
  }

  def update(response: StreamObserver[DiscoveryRequest]) = {
    val prevVersion = applicationStorage.version

    log.info(s"Requesting state update. Current version: $prevVersion")

    val request = DiscoveryRequest(
      versionInfo = prevVersion,
      node = Some(Node()),
      typeUrl = typeUrl
    )
    response.onNext(request)
  }

  override def preStart() = {
    log.debug("Starting")
  }

  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.error(reason, "Restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
  }

  private def prettyPrintGraph(executionGraph: ExecutionGraph) = {
    executionGraph.stages.map { stage =>
      s"{${stage.stageId}[${stage.services.length}]}"
    }.mkString(",")
  }

  private def prettyPrintApps(applications: Seq[ProtoApplication]) = {
    applications.map { app =>
      s"Application(id=${app.id}, name=${app.name}, namespace=${app.namespace}, kafkastreaming=${app.kafkaStreaming}," +
        s"graph=${app.executionGraph.map(prettyPrintGraph)})"
    }.mkString("\n")
  }

}

object XDSActor {

  case object Tick

  case object GetUpdates

  def props(
    sidecarConfig: SidecarConfig,
    applicationStorage: ApplicationStorage
  ) = Props(classOf[XDSActor], sidecarConfig, applicationStorage)
}
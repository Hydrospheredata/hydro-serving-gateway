package io.hydrosphere.serving.gateway.discovery.application

import java.time.Instant

import cats.effect.syntax.effect._
import cats.syntax.functor._
import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import cats.effect.Effect
import envoy.api.v2.core.Node
import envoy.api.v2.{DiscoveryRequest, DiscoveryResponse}
import envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc
import io.grpc.stub.StreamObserver
import io.grpc.{ClientInterceptors, ManagedChannelBuilder}
import io.hydrosphere.serving.gateway.config.SidecarConfig
import io.hydrosphere.serving.gateway.discovery.application.XDSActor.{GetUpdates, Tick}
import io.hydrosphere.serving.gateway.persistence.application.{ApplicationStorage, StoredApplication}
import io.hydrosphere.serving.grpc.AuthorityReplacerInterceptor
import io.hydrosphere.serving.manager.grpc.applications.{ExecutionGraph, Application => ProtoApplication}
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration._
import scala.util.Try

class XDSApplicationUpdateService[F[_]: Effect](
  applicationStorage: ApplicationStorage[F],
  sidecarConfig: SidecarConfig
)(implicit actorSystem: ActorSystem) extends Logging {

  val actor = actorSystem.actorOf(XDSActor.props(sidecarConfig, applicationStorage))

  def getUpdates(): Unit = {
    actor ! GetUpdates
  }
}

class XDSActor[F[_]: Effect](
  sidecarConfig: SidecarConfig,
  applicationStorage: ApplicationStorage[F]
) extends Actor with ActorLogging {

  import context._

  val typeUrl = "type.googleapis.com/io.hydrosphere.serving.manager.grpc.applications.Application"

  private val tickTimer = context.system.scheduler.schedule(10.seconds, 15.seconds, self, Tick)
  private var lastResponse = Instant.MIN

  val observer = new StreamObserver[DiscoveryResponse] {
    override def onError(t: Throwable): Unit = {
      log.error("Application stream exception: {}", t.getMessage)
      context become connecting
    }

    override def onCompleted(): Unit = {
      log.debug("Application stream closed")
      context become connecting
    }

    override def onNext(value: DiscoveryResponse): Unit = {
      log.debug(s"Discovery stream update: $value")

      lastResponse = Instant.now()

      if (value.typeUrl == typeUrl) {
        val applications = value.resources.flatMap { resource =>
          Try(resource.unpack(ProtoApplication)).toOption
        }
        log.info(s"Discovered applications:\n${prettyPrintApps(applications)}")
        val parsed = applications
          .map(app => app -> StoredApplication.fromProto(app))
          .toMap
        val mapped = parsed
          .values
          .flatMap(_.toOption)
          .toSeq
        val errors = parsed
          .filter(_._2.isFailure)
          .mapValues(_.failed.get)

        errors.foreach(x => log.error(x._2, s"Error parsing application ${x._1.name}"))

        applicationStorage.update(mapped, value.versionInfo)
      } else {
        log.debug(s"Got $value message")
      }
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

        val sidecarChannel = ClientInterceptors
          .intercept(builder.build, new AuthorityReplacerInterceptor)

        log.debug(s"Created a channel: ${sidecarChannel.authority()}")

        val xDSClient = AggregatedDiscoveryServiceGrpc.stub(sidecarChannel)
        val result = xDSClient.streamAggregatedResources(observer)
        context become listening(result)
      } catch {
        case err: Exception => log.warning(s"Can't connect: $err")
      }
  }

  def listening(response: StreamObserver[DiscoveryRequest]): Receive = {
    case GetUpdates => update(response)
    case Tick =>
      val now = Instant.now()
      val lastRequiredResponse = now.minusSeconds(sidecarConfig.xdsSilentRestartSeconds)
      if (lastResponse.isBefore(lastRequiredResponse)) {
        log.warning(s"Didn't get XDS responses in ${sidecarConfig.xdsSilentRestartSeconds} seconds. Possible stream error. Restarting...")
        context.become(connecting)
      }
      update(response)
  }

  def update(response: StreamObserver[DiscoveryRequest]) = {
    val f = for {
      version <- applicationStorage.version
    } yield {
      log.debug(s"Requesting state update. Current version: $version")
      val request = DiscoveryRequest(
        versionInfo = version,
        node = Some(Node()),
        typeUrl = typeUrl
      )
      response.onNext(request)
      request
    }
    f.toIO.unsafeToFuture()
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

  def props[F[_]: Effect](
    sidecarConfig: SidecarConfig,
    applicationStorage: ApplicationStorage[F]
  ) = Props(new XDSActor[F](sidecarConfig, applicationStorage))
}
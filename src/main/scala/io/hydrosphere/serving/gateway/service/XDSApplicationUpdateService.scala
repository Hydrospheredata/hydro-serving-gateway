package io.hydrosphere.serving.gateway.service

import akka.actor.{Actor, ActorLogging, ActorSystem, Cancellable, Props}
import envoy.api.v2.core.Node
import envoy.api.v2.{DiscoveryRequest, DiscoveryResponse}
import envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryService
import io.grpc.stub.StreamObserver
import io.hydrosphere.serving.gateway.service.XDSActor.{GetUpdates, Tick}
import io.hydrosphere.serving.manager.grpc.applications.{Application => ProtoApplication}
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration._
import scala.util.Try

class XDSApplicationUpdateService(
  applicationStorage: ApplicationStorage,
  xDSClient: AggregatedDiscoveryService
)(implicit actorSystem: ActorSystem) extends Logging {

  val actor = actorSystem.actorOf(XDSActor.props(xDSClient, applicationStorage))

  val typeUrl = "type.googleapis.com/io.hydrosphere.serving.manager.grpc.applications.Application"

  def getUpdates(): Unit = {
    actor ! GetUpdates
  }
}

class XDSActor(
  xDSClient: AggregatedDiscoveryService,
  applicationStorage: ApplicationStorage
) extends Actor with ActorLogging {

  import context._

  val observer = new StreamObserver[DiscoveryResponse] {
    override def onError(t: Throwable): Unit = {
      log.error("Application stream exception: {}", t.getMessage)
      context become connecting(timer())
    }

    override def onCompleted(): Unit = log.info("Application stream closed")

    override def onNext(value: DiscoveryResponse): Unit = {
      log.info(s"Discovery stream update: $value")

      val applications = value.resources.flatMap { resource =>
        Try(resource.unpack(ProtoApplication)).toOption
      }
      log.info(s"Discovered applications: $applications")
      applicationStorage.update(applications, value.versionInfo)
    }
  }

  val typeUrl = "type.googleapis.com/io.hydrosphere.serving.manager.grpc.applications.Application"

  private def timer() = {
    context.system.scheduler.scheduleOnce(3.seconds, self, Tick)
  }

  final override def receive: Receive = connecting(timer())

  def connecting(timer: Cancellable): Receive = {
    case Tick =>
      log.info(s"Connecting to stream")
      try {
        val result = xDSClient.streamAggregatedResources(observer)
        timer.cancel()
        update(result)
        context become listening(result)
      } catch {
        case err: Exception => log.warning(s"Can't connect: $err")
      }
  }

  def listening(response: StreamObserver[DiscoveryRequest]): Receive = {
    case GetUpdates => update(response)
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
}

object XDSActor {

  case object Tick

  case object GetUpdates

  def props(
    xDSClient: AggregatedDiscoveryService,
    applicationStorage: ApplicationStorage
  ) = Props(classOf[XDSActor], xDSClient, applicationStorage)
}
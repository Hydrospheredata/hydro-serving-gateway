package io.hydrosphere.serving.gateway.service

import com.google.protobuf.any.Any
import envoy.api.v2.core.Node
import envoy.api.v2.{DiscoveryRequest, DiscoveryResponse}
import envoy.service.discovery.v2.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryService
import io.grpc.stub.StreamObserver
import io.hydrosphere.serving.gateway.util.ThreadUtil
import io.hydrosphere.serving.manager.grpc.applications.{Application => ProtoApplication}
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration._
import scala.util.Try

class XDSApplicationUpdateService(
  applicationStorage: ApplicationStorage,
  xDSClient: AggregatedDiscoveryService
) extends Logging {

  val typeUrl = "type.googleapis.com/io.hydrosphere.manager.Application"

  val request = new StreamObserver[DiscoveryResponse] {
    override def onError(t: Throwable): Unit = {
      logger.error("Application stream exception", t)
      getUpdates()
    }

    override def onCompleted(): Unit = logger.info("Application stream closed")

    override def onNext(value: DiscoveryResponse): Unit = {
      logger.info(s"Discovery stream update: $value")

      val applications = value.resources.flatMap { resource =>
        Try(resource.unpack(ProtoApplication)).toOption
      }
      logger.info(s"Discovered applications: $applications")
      applicationStorage.update(applications, value.versionInfo)
    }
  }

  val response: StreamObserver[DiscoveryRequest] = ThreadUtil.retryDecrease(10.seconds, 1.second) {
    logger.info(s"Connecting to stream")
    val x = xDSClient.streamAggregatedResources(request)
    logger.info("Connected")
    x
  }

  def getUpdates(): Unit = response.onNext {

    val prevVersion = applicationStorage.version

    logger.info(s"Requesting state update. Current version: $prevVersion")

    DiscoveryRequest(
      versionInfo = prevVersion,
      node = Some(
        Node(
          //            id="applications"
        )
      ),
      //resourceNames = Seq("one", "two"),
      typeUrl = typeUrl)
  }
}
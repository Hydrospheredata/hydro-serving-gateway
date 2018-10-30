package io.hydrosphere.serving.gateway.grpc

import io.grpc.netty.NettyServerBuilder
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.service.application.ApplicationExecutionService
import io.hydrosphere.serving.gateway.util.GrpcUtil.BuilderWrapper
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.{ExecutionContext, Future}

class GrpcApi(
  appConfig: ApplicationConfig,
  gatewayPredictionService: ApplicationExecutionService[Future]
)(implicit ec: ExecutionContext) extends Logging {

  private[this] val builder = BuilderWrapper(NettyServerBuilder
    .forPort(appConfig.grpc.port)
    .maxMessageSize(appConfig.grpc.maxMessageSize))

  val predictionService = new GrpcPredictionServiceImpl(gatewayPredictionService)

  val server = builder.addService(PredictionServiceGrpc.bindService(predictionService, ec)).build

  logger.info(s"Starting GRPC API server @ 0.0.0.0:${appConfig.grpc.port}")
  server.start()
}

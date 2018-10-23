package io.hydrosphere.serving.gateway.grpc

import io.grpc.netty.NettyServerBuilder
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.service.ApplicationExecutionService
import io.hydrosphere.serving.gateway.util.GrpcUtil.BuilderWrapper
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.ExecutionContext

class GrpcApi(
  appConfig: ApplicationConfig,
  gatewayPredictionService: ApplicationExecutionService
)(implicit ec: ExecutionContext) extends Logging {

  private[this] val builder = BuilderWrapper(NettyServerBuilder
    .forPort(appConfig.grpcPort)
    .maxMessageSize(appConfig.maxMessageSize))

  val predictionService = new GrpcPredictionService(gatewayPredictionService)

  val server = builder.addService(PredictionServiceGrpc.bindService(predictionService, ec)).build

  logger.info(s"Starting GRPC API server @ 0.0.0.0:${appConfig.grpcPort}")
  server.start()
}

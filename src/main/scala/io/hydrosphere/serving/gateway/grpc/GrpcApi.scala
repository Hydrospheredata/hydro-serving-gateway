package io.hydrosphere.serving.gateway.grpc

import cats.effect.Effect
import io.grpc.netty.NettyServerBuilder
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.service.application.ApplicationExecutionService
import io.hydrosphere.serving.gateway.util.GrpcUtil.BuilderWrapper
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.ExecutionContext

class GrpcApi[F[_]: Effect](
  appConfig: ApplicationConfig,
  gatewayPredictionService: ApplicationExecutionService[F],
  grpcEC: ExecutionContext
) extends Logging {

  private[this] val builder = BuilderWrapper(NettyServerBuilder
    .forPort(appConfig.grpc.port)
    .maxMessageSize(appConfig.grpc.maxMessageSize))

  val predictionService = new GrpcPredictionServiceImpl[F](gatewayPredictionService)

  val server = builder.addService(PredictionServiceGrpc.bindService(predictionService, grpcEC)).build

  logger.info(s"Starting GRPC API server @ 0.0.0.0:${appConfig.grpc.port}")
  server.start()
}

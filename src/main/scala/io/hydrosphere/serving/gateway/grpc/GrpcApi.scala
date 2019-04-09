package io.hydrosphere.serving.gateway.grpc

import cats.effect.{Effect, Resource}
import cats.implicits._
import io.grpc.netty.NettyServerBuilder
import io.hydrosphere.serving.gateway.Logging
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.service.application.ApplicationExecutionService
import io.hydrosphere.serving.gateway.util.GrpcUtil.BuilderWrapper
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc

import scala.concurrent.ExecutionContext

class GrpcApi[F[_]: Effect](
  appConfig: ApplicationConfig,
  gatewayPredictionService: ApplicationExecutionService[F],
  grpcEC: ExecutionContext
) extends Logging {

  private[this] val builder = BuilderWrapper(NettyServerBuilder
    .forPort(appConfig.grpc.port)
    .maxInboundMessageSize(appConfig.grpc.maxMessageSize))

  val predictionService = new GrpcPredictionServiceImpl[F](gatewayPredictionService)

  val server = builder.addService(PredictionServiceGrpc.bindService(predictionService, grpcEC)).build

  def start() = for {
    s <- Effect[F].delay(server.start())
    _ <- Logging.info(s"Starting GRPC API server @ 0.0.0.0:${appConfig.grpc.port}")
  } yield s
}

object GrpcApi {
  def makeAsResource[F[_]](
    appConfig: ApplicationConfig,
    predictService: ApplicationExecutionService[F],
    grpcEC: ExecutionContext
  )(implicit F: Effect[F]) = {
    val res = F.delay(new GrpcApi[F](appConfig, predictService, grpcEC))
    Resource.make(res)(x => F.delay(x.server.shutdown()))
  }
}
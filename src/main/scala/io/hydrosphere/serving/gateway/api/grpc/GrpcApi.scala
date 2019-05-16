package io.hydrosphere.serving.gateway.api.grpc

import cats.effect.{Effect, Resource}
import cats.implicits._
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import io.hydrosphere.serving.gateway.Logging
import io.hydrosphere.serving.gateway.api.GatewayServiceGrpc
import io.hydrosphere.serving.gateway.config.ApplicationConfig
import io.hydrosphere.serving.gateway.execution.ExecutionService
import io.hydrosphere.serving.gateway.util.GrpcUtil.BuilderWrapper
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc

import scala.concurrent.ExecutionContext

class GrpcApi[F[_]: Effect](
  appConfig: ApplicationConfig,
  executionService: ExecutionService[F],
  grpcEC: ExecutionContext
) extends Logging {

  private[this] val builder = BuilderWrapper(NettyServerBuilder
    .forPort(appConfig.grpc.port)
    .maxInboundMessageSize(appConfig.grpc.maxMessageSize))

  val predictionService = new PredictionServiceEndpoint[F](executionService)
  val gatewayService = new GatewayServiceEndpoint[F](executionService)
  builder.addService(PredictionServiceGrpc.bindService(predictionService, grpcEC))
  builder.addService(GatewayServiceGrpc.bindService(gatewayService, grpcEC))

  val server: Server = builder.build

  def start(): F[Server] = for {
    s <- Effect[F].delay(server.start())
    _ <- Logging.info(s"Starting GRPC API server @ 0.0.0.0:${appConfig.grpc.port}")
  } yield s
}

object GrpcApi {
  def makeAsResource[F[_]](
    appConfig: ApplicationConfig,
    predictService: ExecutionService[F],
    grpcEC: ExecutionContext
  )(implicit F: Effect[F]): Resource[F, GrpcApi[F]] = {
    val res = F.delay(new GrpcApi[F](appConfig, predictService, grpcEC))
    Resource.make(res)(x => F.delay(x.server.shutdown()))
  }
}
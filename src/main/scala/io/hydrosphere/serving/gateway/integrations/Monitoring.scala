package io.hydrosphere.serving.gateway.integrations

import java.util.concurrent.Executors

import cats.effect._
import cats.syntax.functor._
import io.grpc.ManagedChannelBuilder
import io.hydrosphere.serving.gateway.config.ApiGatewayConfig
import io.hydrosphere.serving.monitoring.api.{ExecutionInformation, MonitoringServiceGrpc}

import scala.concurrent.duration.Duration

trait Monitoring[F[_]] {
  def send(execInfo: ExecutionInformation): F[Unit]
}

object Monitoring {

  def default[F[_]](cfg: ApiGatewayConfig, deadline: Duration)(implicit F: Async[F]): Monitoring[F] = {
    val executor = Executors.newCachedThreadPool()
    
    val builder = ManagedChannelBuilder.forAddress(cfg.host, cfg.grpcPort).executor(executor)
    builder.enableRetry()
    builder.usePlaintext()
    val channel = builder.build()
    val stub = MonitoringServiceGrpc.stub(channel)

    info: ExecutionInformation => {
      val op = IO.fromFuture(
        IO(stub.withDeadlineAfter(deadline.length, deadline.unit).analyze(info))
      )
      F.liftIO(op).void
    }
  }
}


package io.hydrosphere.serving.gateway.integrations

import java.util.concurrent.Executors

import cats.effect._
import cats.syntax.functor._
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannelBuilder
import io.hydrosphere.serving.gateway.config.ApiGatewayConfig
import io.hydrosphere.serving.gateway.util.AsyncUtil
import io.hydrosphere.monitoring.proto.sonar.entities.ExecutionInformation
import io.hydrosphere.monitoring.proto.sonar.api.MonitoringServiceGrpc

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

trait Monitoring[F[_]] {
  def send(execInfo: ExecutionInformation): F[Unit]
}

object Monitoring {

  def default[F[_]](cfg: ApiGatewayConfig, deadline: Duration, maxMessageSize: Int)(implicit F: Async[F]): Monitoring[F] = {
    val executor = Executors.newCachedThreadPool()

    val builder = ManagedChannelBuilder.forAddress(cfg.host, cfg.grpcPort)
    builder.enableRetry()
    builder.usePlaintext()
    builder.executor(executor)
    val channel = builder.build()
    val stub = MonitoringServiceGrpc.stub(channel)
      .withMaxInboundMessageSize(maxMessageSize)
      .withMaxOutboundMessageSize(maxMessageSize)

    info: ExecutionInformation => {
      AsyncUtil.futureAsync[F, Empty] {
        stub.withDeadlineAfter(deadline.length, deadline.unit).analyze(info)
      }(F, ExecutionContext.fromExecutor(executor))
        .as(F.unit)
    }
  }
}

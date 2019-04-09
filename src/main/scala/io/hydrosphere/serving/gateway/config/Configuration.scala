package io.hydrosphere.serving.gateway.config

import cats.effect.Sync
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration.{Duration, FiniteDuration}

//final case class ManagerConfig(
//  host: String,
//  grpcPort: Int,
//  httpPort: Int,
//  reconnectTimeout: FiniteDuration
//)
//
//final case class MonitoringConfig(
//  host: String,
//  port: Int
//)

final case class GrpcConfig(
  port: Int,
  deadline: Duration,
  maxMessageSize: Int = 4 * 1024 * 1024,
)

final case class HttpConfig(
  port: Int
)

final case class ApiGatewayConfig(
  host: String,
  grpcPort: Int,
  httpPort: Int,
  reconnectTimeout: FiniteDuration
)


final case class ReqStoreConfig(
  enabled: Boolean,
  host: String,
  port: Int,
  schema: String
)

final case class ApplicationConfig(
  grpc: GrpcConfig,
//  manager: ManagerConfig,
  http: HttpConfig,
  shadowingOn: Boolean,
//  monitoring: MonitoringConfig,
  reqstore: ReqStoreConfig,
  apiGateway: ApiGatewayConfig
)

final case class Configuration(
  application: ApplicationConfig,
)


object Configuration extends Logging {
  def load[F[_]](implicit F: Sync[F]) = F.defer {
    F.fromEither {
      pureconfig.loadConfig[Configuration].left.map { x =>
        new IllegalArgumentException(x.toList.toString().mkString("\n"))
      }
    }
  }
}
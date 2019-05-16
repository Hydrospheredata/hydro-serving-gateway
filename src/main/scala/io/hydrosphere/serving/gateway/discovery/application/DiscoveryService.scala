package io.hydrosphere.serving.gateway.discovery.application

import akka.actor.{ActorRef, ActorSystem}
import cats.effect.Effect
import io.hydrosphere.serving.gateway.config.ApiGatewayConfig
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import io.hydrosphere.serving.gateway.persistence.servable.ServableStorage
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration._

class DiscoveryService[F[_]: Effect](
  apiGatewayConf: ApiGatewayConfig,
  clientDeadline: Duration,
  applicationStorage: ApplicationStorage[F],
  servableStorage: ServableStorage[F],
)(implicit actorSystem: ActorSystem) extends Logging {
  val actor: ActorRef = actorSystem.actorOf(DiscoveryWatcher.props[F](apiGatewayConf, clientDeadline, applicationStorage, servableStorage))
}

object DiscoveryService {
  def makeDefault[F[_] : Effect](
    apiGatewayConf: ApiGatewayConfig,
    clientDeadline: Duration,
    applicationStorage: ApplicationStorage[F],
    servableStorage: ServableStorage[F]
  )(implicit as: ActorSystem) = Effect[F].delay {
    new DiscoveryService[F](apiGatewayConf, clientDeadline, applicationStorage, servableStorage)
  }
}
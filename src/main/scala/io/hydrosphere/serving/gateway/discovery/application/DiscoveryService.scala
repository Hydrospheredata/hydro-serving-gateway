package io.hydrosphere.serving.gateway.discovery.application

import akka.actor.{ActorRef, ActorSystem}
import cats.effect.Effect
import io.hydrosphere.serving.gateway.config.ApiGatewayConfig
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration._

class DiscoveryService[F[_]: Effect](
  apiGatewayConf: ApiGatewayConfig,
  clientDeadline: Duration,
  applicationStorage: ApplicationStorage[F]
)(implicit actorSystem: ActorSystem) extends Logging {
  val actor: ActorRef = actorSystem.actorOf(DiscoveryWatcher.props[F](apiGatewayConf, clientDeadline, applicationStorage))
}

object DiscoveryService {
  def makeDefault[F[_] : Effect](
    apiGatewayConf: ApiGatewayConfig,
    clientDeadline: Duration,
    applicationStorage: ApplicationStorage[F]
  )(implicit as: ActorSystem) = Effect[F].delay {
    new DiscoveryService[F](apiGatewayConf, clientDeadline, applicationStorage)
  }
}
package io.hydrosphere.serving.gateway.config

import cats.effect.Sync

final case class Configuration(application: ApplicationConfig)

object Configuration  {
  final case class ConfigurationLoadingException(msg: String) extends Throwable

  def load[F[_]](implicit F: Sync[F]) = F.defer {
    F.fromEither {
      pureconfig.loadConfig[Configuration].left.map { x =>
        ConfigurationLoadingException(x.toList.mkString("\n"))
      }
    }
  }
}
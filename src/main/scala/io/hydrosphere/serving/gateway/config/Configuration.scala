package io.hydrosphere.serving.gateway.config

import cats.effect.Sync

final case class Configuration(application: ApplicationConfig)

object Configuration  {
  def load[F[_]](implicit F: Sync[F]) = F.defer {
    F.fromEither {
      pureconfig.loadConfig[Configuration].left.map { x =>
        new IllegalArgumentException(x.toList.toString().mkString("\n"))
      }
    }
  }
}
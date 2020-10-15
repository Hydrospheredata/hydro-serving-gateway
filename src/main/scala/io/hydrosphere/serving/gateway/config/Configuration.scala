package io.hydrosphere.serving.gateway.config

import cats.effect.Sync
import pureconfig.ConfigSource
import pureconfig.generic.auto._

final case class Configuration(application: ApplicationConfig)

object Configuration  {
  final case class ConfigurationLoadingException(msg: String) extends Throwable

  def load[F[_]](implicit F: Sync[F]) = F.defer {
    F.fromEither {
      ConfigSource.default.load[Configuration].left.map { x =>
        ConfigurationLoadingException(x.toList.mkString("\n"))
      }
    }
  }
}
package io.hydrosphere.serving.gateway.grpc.reqstore

import akka.http.scaladsl.model.{HttpHeader, Uri, headers}
import io.hydrosphere.serving.gateway.config.{HttpServiceAddr, SidecarConfig}

sealed trait Destination {
  def host: String
  def port: Int
  def additionalHeaders: List[HttpHeader]
  def uri: Uri
}

object Destination {

  final case class EnvoyRoute(
    host: String,
    port: Int,
    name: String,
    schema: String
  ) extends Destination {

    override val additionalHeaders: List[HttpHeader] = List(headers.Host(name))
    override val uri: Uri = Uri(s"$schema://$host:$port")

  }
  final case class HostPort(
    host: String,
    port: Int,
    schema: String
  ) extends Destination {
    override val additionalHeaders: List[HttpHeader] = List.empty
    override val uri: Uri = Uri(s"$schema://$host:$port")
  }

  def fromHttpServiceAddr(
    addr: HttpServiceAddr,
    envoyConf: SidecarConfig
  ): Destination =
    addr match {
      case HttpServiceAddr.EnvoyRoute(name) =>
        Destination.EnvoyRoute(envoyConf.host, envoyConf.port, name, "http")
      case HttpServiceAddr.RealAddress(host, port, schema) =>
        Destination.HostPort(host, port, schema)
    }
}


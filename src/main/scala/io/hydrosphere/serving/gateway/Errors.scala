package io.hydrosphere.serving.gateway

sealed trait GatewayError extends Throwable {
  def msg: String
  override def getMessage: String = msg
}

case class NotFound(msg: String) extends GatewayError

case class InvalidArgument(msg: String) extends GatewayError

case class InternalError(msg: String) extends GatewayError
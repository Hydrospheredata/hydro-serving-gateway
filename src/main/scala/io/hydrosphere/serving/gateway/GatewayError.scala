package io.hydrosphere.serving.gateway

sealed trait GatewayError extends Throwable with Product with Serializable {
  def msg: String
  override def getMessage: String = msg
}

object GatewayError {

  case class NotFound(msg: String) extends GatewayError

  case class InvalidArgument(msg: String) extends GatewayError

  case class InternalError(msg: String) extends GatewayError

  case class InvalidTensorShape(msg: String) extends GatewayError

}
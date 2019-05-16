package io.hydrosphere.serving.gateway

import cats.effect.Sync
import org.apache.logging.log4j.scala.Logger

trait Logging {

  /**
    * A [[Logger]] named according to the class.
    */
  protected implicit val logger: Logger = Logger(getClass)

}

object Logging {
  def info[F[_]: Sync](msg: String)(implicit logger: Logger) = Sync[F].delay(logger.info(msg))
  def warn[F[_]: Sync](msg: String)(implicit logger: Logger) = Sync[F].delay(logger.warn(msg))
  def error[F[_]: Sync](msg: String)(implicit logger: Logger) = Sync[F].delay(logger.error(msg))
  def error[F[_]: Sync](msg: String, cause: Throwable)(implicit logger: Logger) = Sync[F].delay(logger.error(msg, cause))
  def debug[F[_]: Sync](msg: String)(implicit logger: Logger) = Sync[F].delay(logger.debug(msg))
}
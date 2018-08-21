package io.hydrosphere.serving.gateway.util

import scala.concurrent.duration.Duration

object ThreadUtil {

  /**
    * Invokes callback until it returns correct value.
    *
    * If exception is caught, sleeps for `sleep` duration.
    * The next iteration will repeat the process, but with `sleep - delta` duration of sleep
    *
    * @param sleep duration of thread sleep time
    * @param delta duration change for the next iteration sleep
    * @param fn callback
    * @tparam T callback result type
    * @return callback result
    */
  @annotation.tailrec
  def retryDecrease[T](sleep: Duration, delta: Duration)(fn: => T): T = {
    val r = try {
      Some(fn)
    } catch {
      case _: Exception if sleep > Duration.Zero => None
    }

    r match {
      case Some(x) => x
      case None =>
        Thread.sleep(sleep.toMillis)
        retryDecrease(sleep - delta, delta)(fn)
    }
  }
}

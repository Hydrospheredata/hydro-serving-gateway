package io.hydrosphere.serving.gateway.util

import java.util.concurrent.TimeoutException

import scala.concurrent.duration.Duration
import scala.concurrent.duration._

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

  @annotation.tailrec
  def retryExpBackoff[T](init: Duration, max: Duration)(fn : => T): T = {
    val r = try {
      Some(fn)
    } catch {
      case _: Exception if init < max => None
      case _: Exception => throw new TimeoutException()
    }

    r match {
      case Some(x) => x
      case None =>
        Thread.sleep(init.toMillis)
        retryExpBackoff(Math.exp(init.toMillis).milliseconds, max)(fn)
    }
  }
}

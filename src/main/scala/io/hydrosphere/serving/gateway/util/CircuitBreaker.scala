package io.hydrosphere.serving.gateway.util

import cats.effect.concurrent.Ref
import cats.effect.implicits._
import cats.effect.{Concurrent, Sync, Timer}
import cats.implicits._

import scala.concurrent.duration.FiniteDuration
import akka.io.IO

trait CircuitBreaker[F[_]] {
  def use[A](f: => F[A]): F[A]
}

object CircuitBreaker {

  sealed trait Status
  object Status {
    case object Closed extends Status
    case object HalfOpen extends Status
    case object Open extends Status
  }

  def apply[F[_]](
    callTimeout: FiniteDuration,
    maxErrors: Int,
    resetTimeout: FiniteDuration,
  )(listener: Status => F[Unit])(implicit F: Concurrent[F], timer: Timer[F]): CircuitBreaker[F] = {

    new CircuitBreaker[F] {

      private val stateRef: Ref[F, State[F]] = Ref.unsafe(State.Closed(Ref.unsafe(0)))
      private def cbOpenError = new Exception("Circuit breaker is open")

      override def use[A](f: => F[A]): F[A] = {
        stateRef.get.flatMap({
          case State.Closed(errors) => callClosed(f, errors)
          case State.HalfOpen(tried) =>
            tried.get.flatMap({
              case true => F.raiseError(cbOpenError)
              case false => tried.tryUpdate(_ => true).ifM(callHalfOpen(f), F.raiseError(cbOpenError))
            })
          case State.Open() => F.raiseError(cbOpenError)
        })
      }

      private def callClosed[A](f: => F[A], errors: Ref[F, Int]): F[A] = callAnd(f)(errors.set(0))(onClosedErr)
      private def callHalfOpen[A](f: => F[A]): F[A] = callAnd(f)(toClosed)(toOpen)

      private def onClosedErr: F[Unit] = {
        for {
          curr <- stateRef.get
          _ <- curr match {
            case State.Closed(errors) =>
              errors.modify(i => {
                val next = i + 1
                (next, next)
              }).flatMap(i => if (i >= maxErrors) toOpen else F.unit)
            case _ => F.unit
          }
        } yield ()
      }

      private def toClosed: F[Unit] = stateRef.set(State.freshClosed) >> notifyListener(Status.Closed)

      private def toOpen: F[Unit] = {
        stateRef.tryUpdate(_ => State.Open()).ifM(scheduleTimeout >> notifyListener(Status.Open), F.unit)
      }

      private def scheduleTimeout: F[Unit] = {
        val reset = timer.sleep(resetTimeout) >> stateRef.set(State.freshHalfOpen) >> notifyListener(Status.HalfOpen)
        reset.start.void
      }

      private def callAnd[A](f: => F[A])(onSucc: => F[Unit])(onErr: => F[Unit]): F[A] = {
        f.timeout(callTimeout)
          .attempt
          .flatTap({
            case Left(_) => onErr
            case Right(_) => onSucc
          })
          .rethrow
      }

      private def notifyListener(st: Status): F[Unit] = listener(st).start.void
    }
  }

  sealed trait State[F[_]]
  object State {
    final case class Open[F[_]]() extends State[F]
    final case class HalfOpen[F[_]](tried: Ref[F, Boolean]) extends State[F]
    final case class Closed[F[_]](errors: Ref[F, Int]) extends State[F]

    def freshClosed[F[_]: Sync]: Closed[F] = Closed[F](Ref.unsafe[F, Int](0))
    def freshHalfOpen[F[_]: Sync]: HalfOpen[F] = HalfOpen[F](Ref.unsafe[F, Boolean](false))
  }
}

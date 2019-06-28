package io.hydrosphere.serving.gateway.util

import cats._
import cats.implicits._
import cats.effect._
import io.hydrosphere.serving.gateway.GenericTest

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class CircuitBreakerSpec extends GenericTest {

  implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)


  it("less than max errors") {
    val cb = CircuitBreaker[IO](1 millis, 3, 10 millis)(_ => IO.unit)
    val effs = List(
      IO.pure(42),
      IO.raiseError(new Exception),
      IO.raiseError(new Exception),
      IO.pure(42)
    )

    val out = effs.traverse(a => cb.use(a).attempt).unsafeRunSync
    out.last shouldBe Right(42)
  }

  
  it("more than max error") {
    val cb = CircuitBreaker[IO](1 millis, 3, 100 millis)(p_ => IO.unit)
    val effs = List(
      IO.pure(42),
      IO.raiseError(new Exception),
      IO.raiseError(new Exception),
      IO.raiseError(new Exception),
      IO.pure(42),
    )
    val out = effs.traverse(a => cb.use(a).attempt).unsafeRunSync
    out.last.isLeft shouldBe true
  }
  
  it("timeout") {
    val cb = CircuitBreaker[IO](1 millis, 3, 100 millis)(_ => IO.unit)
    val effs = List(
      IO.pure(42),
      IO.sleep(2 millis),
      IO.sleep(2 millis),
      IO.sleep(2 millis),
      IO.sleep(2 millis),
      IO.pure(42),
    )
    val out = effs.traverse(a => cb.use(a).attempt).unsafeRunSync
    out.last.isLeft shouldBe true
  }

  it("resets to halfopen") {
    val cb = CircuitBreaker[IO](1 millis, 3, 1 millis)(_ => IO.unit)
    val effs = List(
      IO.pure(42),
      IO.sleep(2 millis),
      IO.sleep(2 millis),
      IO.sleep(2 millis),
      IO.sleep(2 millis),
      IO.pure(42),
    )
    val f = 
      for {
        _ <- effs.traverse(a => cb.use(a).attempt)
        _ <- IO.sleep(10 millis)        
        v <- cb.use(IO.pure(42))
      } yield v 

    f.unsafeRunSync() shouldBe 42
  }
  

}
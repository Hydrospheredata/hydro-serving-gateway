package io.hydrosphere.serving.gateway

import cats.data.EitherT
import io.hydrosphere.serving.model.api.HFResult
import io.hydrosphere.serving.model.api.Result.HError
import org.mockito.Mockito
import org.scalatest.{Assertion, AsyncFunSpecLike, Matchers}
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.Future

trait GenericTest extends AsyncFunSpecLike with Matchers with MockitoSugar {

  def when[T](methodCall: T) = Mockito.when(methodCall)

  protected def eitherAssert(body: => HFResult[Assertion]): Future[Assertion] = {
    body.map {
      case Left(err) =>
        fail(err.message)
      case Right(asserts) =>
        asserts
    }
  }

  protected def eitherTAssert(body: => EitherT[Future, HError, Assertion]): Future[Assertion] = {
    eitherAssert(body.value)
  }
}
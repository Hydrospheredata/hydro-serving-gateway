package io.hydrosphere.serving.gateway

import org.mockito.Mockito
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{AsyncFunSpecLike, Matchers}


trait GenericTest extends AsyncFunSpecLike with Matchers with MockitoSugar {
  def when[T](methodCall: T) = Mockito.when(methodCall)
}
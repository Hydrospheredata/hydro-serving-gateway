package io.hydrosphere.serving.gateway.grpc.reqstore

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.util.ByteString
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import scalapb.GeneratedMessage
import scalapb.descriptors.ScalaType

case class ByteSource(
  size: Int,
  source: Source[ByteString, NotUsed]
) {

  def ++(other: ByteSource): ByteSource =
    ByteSource(size + other.size, source ++ other.source)
}

trait ToByteSource[A] {
  def asByteSource(a: A): ByteSource
}

object ToByteSource {

  private val pbMessageTbs: ToByteSource[scalapb.GeneratedMessage] =
    new ToByteSource[scalapb.GeneratedMessage] {
      val empty = ByteSource(1, Source.single(ByteString(0)))

      def asByteSource(a: scalapb.GeneratedMessage): ByteSource = {
        val arr = a.toByteArray
        if (arr.length == 0) empty
        else ByteSource(arr.length + 1, Source(List(ByteString(1), ByteString(arr))))
      }

    }

  implicit def forPbMessage[A <: scalapb.GeneratedMessage]: ToByteSource[A] =
    pbMessageTbs.asInstanceOf[ToByteSource[A]]

  implicit val forResponseOrError: ToByteSource[ResponseOrError] = {
    new ToByteSource[ResponseOrError] {
      def asByteSource(a: ResponseOrError): ByteSource = {
        val head = ByteSource(1, Source.single(ByteString(a.number)))
        val enc = a match {
          case ResponseOrError.Empty => None
          case ResponseOrError.Response(r) => Some(pbMessageTbs.asByteSource(r))
          case ResponseOrError.Error(e) => Some(pbMessageTbs.asByteSource(e))
        }

        enc.map(bs => head ++ bs).getOrElse(head)
      }
    }
  }

  implicit def forTuple2[A, B](implicit aTbs: ToByteSource[A], bTbs: ToByteSource[B]): ToByteSource[(A, B)] = {
    new ToByteSource[(A, B)] {
      def asByteSource(x: (A, B)): ByteSource = {
        val aBs = aTbs.asByteSource(x._1)
        val bBs = bTbs.asByteSource(x._2)
        val head = ByteSource(2, Source.single(ByteString(aBs.size, bBs.size)))
        head ++ aBs ++ bBs
      }
    }
  }
}

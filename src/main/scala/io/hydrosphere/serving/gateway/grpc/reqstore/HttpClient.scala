package io.hydrosphere.serving.gateway.grpc.reqstore

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.scaladsl.{Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import cats.effect._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.concurrent.duration._
import scala.util._

case class HttpRsp(
  code: Int,
  body: Array[Byte]
)

trait HttpClient[F[_]] {
  def send(req: HttpRequest): F[HttpRsp]
  def close: F[Unit]
}

object HttpClient {

  type Callback[A] = Either[Throwable, A] => Unit
  type AkkaQueue[A, B] = SourceQueueWithComplete[(A, B)]
  type ReqQueueWithDone[F[_]] = (SourceQueueWithComplete[(HttpRequest, Callback[HttpRsp])], F[Unit])

  def cachedPool[F[_]](
    host: String,
    port: Int,
    poolSize: Int
  )(implicit F: Async[F]): F[HttpClient[F]] = F.delay {

    implicit val as = ActorSystem(s"simple-http-client-$host$port")

    val am = ActorMaterializer()
    val (queue, term) = akkaResources.createQueue(am, host, port, poolSize)

    new HttpClient[F] {
      override def send(req: HttpRequest): F[HttpRsp] = F.asyncF(cb => {
        val submit = IO {
          queue.offer(req -> cb)
        }
        F.liftIO(IO.fromFuture(submit)).map {
          case QueueOfferResult.Dropped     => cb(Left(new RuntimeException("Queue is full")))
          case QueueOfferResult.Failure(ex) => cb(Left(ex))
          case QueueOfferResult.QueueClosed => cb(Left(new RuntimeException("Queue was closed")))
          case QueueOfferResult.Enqueued    => ()
        }
      })

      override def close: F[Unit] = {
        val closeAs = IO(as.terminate())
        F.delay(queue.complete()).map(_ => term)
          .flatMap(_ => F.liftIO(IO.fromFuture(closeAs)))
          .void
      }
    }
  }


  object akkaResources {

    def createQueue[F[_]](
      am: ActorMaterializer,
      host: String,
      port: Int,
      poolSize: Int
    )(implicit F: Async[F]): ReqQueueWithDone[F] = {

      implicit val mat = am
      implicit val sys = am.system
      implicit val ex = am.system.dispatcher

      val flow = Http().cachedHostConnectionPool[Callback[HttpRsp]](host, port)

      val (q, done) = Source.queue[(HttpRequest, Callback[HttpRsp])](poolSize, OverflowStrategy.dropNew)
        .via(flow)
        .toMat(Sink.foreach({
          case (Success(resp), cb) =>
            resp.entity.toStrict(1 second)(am).onComplete{
              case Success(strict) =>
                val simpleRsp = HttpRsp(resp.status.intValue(), strict.data.toArray)
                cb(Right(simpleRsp))
              case Failure(e) => cb(Left(e))
            }
          case (Failure(e), cb) => cb(Left(e))
        }))(Keep.both)
        .run()

      val lifted = F.liftIO(IO.fromFuture(IO(done))).void
      q -> lifted
    }
  }
}

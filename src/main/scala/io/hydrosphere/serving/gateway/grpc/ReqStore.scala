package io.hydrosphere.serving.gateway.grpc

import java.io.ByteArrayOutputStream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{JavaFlowSupport, Keep, Sink, Source}
import akka.util.ByteString
import cats.effect.{Async, Effect, IO}
import cats.syntax.functor._
import cats.syntax.either._
import com.google.protobuf.CodedOutputStream
import io.hydrosphere.serving.monitoring.monitoring.TraceData
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest

import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._


sealed trait Destination {
  def host: String
  def port: Int
  def additionalHeaders: List[HttpHeader]
  def uri: Uri
}

object Destination {

  final case class EnvoyRoute(
    host: String,
    port: Int,
    name: String,
    schema: String
  ) extends Destination {

    override val additionalHeaders: List[HttpHeader] = List(headers.Host(name))
    override val uri: Uri = Uri(s"$schema://$host:$port")

  }
  final case class HostPort(
    host: String,
    port: Int,
    schema: String
  ) extends Destination {
    override val additionalHeaders: List[HttpHeader] = List.empty
    override val uri: Uri = Uri(s"$schema://$host:$port")
  }
}

trait ReqStore[F[_]] {
  def save(name: String, request: PredictRequest): F[TraceData]
}

object ReqStore {
  import spray.json._

  implicit val traceDataFormat = new JsonReader[TraceData] {

    private def extractLong(v: JsValue): Option[Long] = v match {
      case n: JsNumber => Try(n.value.toLongExact).toOption
      case _ => None
    }

    override def read(json: JsValue): TraceData = {
      json match {
        case x @ JsObject(fields) =>
          val mTs = fields.get("ts").flatMap(extractLong)
          val mUid = fields.get("uniq").flatMap(extractLong)
          (mTs, mUid) match {
            case (Some(ts), Some(uid)) => TraceData(ts, uid)
            case _ => throw new DeserializationException(s"Invalid trace data format: $x")
          }
        case x => throw new DeserializationException(s"Invalid trace data format: $x")
      }
    }
  }

  type Callback[A] = Either[Throwable, A] => Unit

  def create[F[_]](destination: Destination)(implicit F: Async[F]): ReqStore[F] = {
    implicit val as = ActorSystem("req-store")
    implicit val am = ActorMaterializer()

    val flow = Http().cachedHostConnectionPool[Callback[TraceData]](destination.host, destination.port)
    val queue =
      Source.queue[(HttpRequest, Callback[TraceData])](1000, OverflowStrategy.dropNew)
        .via(flow)
        .toMat(Sink.foreach({
          case (Success(resp), cb) =>
            println(resp)
            resp.entity.toStrict(1 second)
              .map(entity => {
                val decoded = Either.catchNonFatal {
                  val jsonS = new String(entity.data.toArray)
                  println(jsonS)
                  jsonS.parseJson.convertTo[TraceData]
                }
                decoded match {
                  case Left(e) => cb(Left(e))
                  case Right(data) => cb(Right(data))
                }
              })(as.dispatcher)
          case (Failure(e), cb) => cb(Left(e))
        }))(Keep.left)
        .run()

    new ReqStore[F] {
      def save(name: String, request: PredictRequest): F[TraceData] = {
        val (byteSource, size) = toBytes(request)

        val entity = HttpEntity.Default(
          ContentTypes.`application/octet-stream`,
          size,
          byteSource
        )

        val httpReq = HttpRequest(
          HttpMethods.POST,
          Uri("/test/put"),
          entity = entity,
          headers = destination.additionalHeaders
        )

        F.asyncF(cb => {
          val submit = IO {
            queue.offer(httpReq -> cb)
          }
          F.liftIO(IO.fromFuture(submit)).map {
            case QueueOfferResult.Dropped     => cb(Left(new RuntimeException("Queue is full")))
            case QueueOfferResult.Failure(ex) => cb(Left(ex))
            case QueueOfferResult.QueueClosed => cb(Left(new RuntimeException("Queue was closed")))
            case QueueOfferResult.Enqueued    =>
          }
        })

      }
    }
  }


  val empty = Source.single(ByteString(0))

  def toBytes(req: PredictRequest): (Source[ByteString, NotUsed], Int) = {
    val arr = req.toByteArray
    if (arr.length == 0) empty -> 1
    else Source(List(ByteString(1), ByteString(arr))) -> (arr.length + 1)
  }
}

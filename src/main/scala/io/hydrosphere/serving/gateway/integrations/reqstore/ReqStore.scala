package io.hydrosphere.serving.gateway.integrations.reqstore

import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString
import cats.effect.{Async, ContextShift, IO, Sync}
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.grpc.{ClientInterceptors, ManagedChannelBuilder}
import io.hydrosphere.serving.gateway.config.ReqStoreConfig
import io.hydrosphere.serving.grpc.{AuthorityReplacerInterceptor, Headers}
import io.hydrosphere.reqstore.reqstore_service.{SaveRequest, TimemachineGrpc}
import io.hydrosphere.serving.gateway.util.AsyncUtil
import io.hydrosphere.serving.monitoring.metadata.TraceData
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

trait ReqStore[F[_], A] {
  def save(name: String, a: A): F[TraceData]
}

object ReqStore extends Logging {

  import jsonCodecs._
  import spray.json._

  def grpc[F[_], A](cfg: ReqStoreConfig)(implicit F: Async[F], tbs: ToByteSource[A]): F[ReqStore[F, A]] = F.delay {
    new ReqStore[F, A] {

      implicit val system = ActorSystem("QuickStart")
      implicit val materializer = Materializer(system)

      private lazy val grpcChannel = {
        val deadline = 2 minutes
        val builder = ManagedChannelBuilder.forAddress(cfg.host, cfg.port)
        builder.enableRetry()
        builder.usePlaintext()
        builder.keepAliveTimeout(deadline.length, deadline.unit)
        ClientInterceptors.intercept(builder.build(), new AuthorityReplacerInterceptor +: Headers.interceptors: _*)
      }

      private lazy val reqstoreChannel = TimemachineGrpc.stub(grpcChannel)

      override def save(name: String, a: A): F[TraceData] = for {
        data <- AsyncUtil.futureAsync(tbs.asByteSource(a).source.runFold(ByteString.empty)(_ ++ _))
        id <- AsyncUtil.futureAsync(reqstoreChannel.save(SaveRequest(name, false, com.google.protobuf.ByteString.copyFrom(data.asByteBuffer), Instant.now().toEpochMilli)))
      } yield TraceData(id.timestamp, id.unique)
    }
  }
  
  def create[F[_], A](cfg: ReqStoreConfig)(
    implicit
    F: Async[F],
    cs: ContextShift[IO],
    tbs: ToByteSource[A]
  ): F[ReqStore[F, A]] = {
    HttpClient.cachedPool(cfg.host, cfg.port, 200) map (create0(cfg.prefix, _))
  }

  def create0[F[_], A](prefix: String, client: HttpClient[F])(
    implicit
    F: Sync[F],
    tbs: ToByteSource[A]
  ): ReqStore[F, A] = {
    new ReqStore[F, A] {
      override def save(name: String, a: A): F[TraceData] = F.defer {
        val byteSource = tbs.asByteSource(a)

        val entity = HttpEntity.Default(
          ContentTypes.`application/octet-stream`,
          byteSource.size,
          byteSource.source
        )

        val httpReq = HttpRequest(
          HttpMethods.POST,
          Uri(s"$prefix$name/put"),
          entity = entity,
        )

        client.send(httpReq).flatMap(rsp => {
          val data = new String(rsp.body)
          if (rsp.code == 200) {
            val decode = Either.catchNonFatal(data.parseJson.convertTo[TraceData])
            F.fromEither(decode)
          } else F.raiseError(new RuntimeException(s"Request $httpReq failed, ${rsp}"))
        })
      }

    }
  }

}

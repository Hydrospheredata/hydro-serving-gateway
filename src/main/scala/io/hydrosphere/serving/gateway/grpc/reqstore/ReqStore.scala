package io.hydrosphere.serving.gateway.grpc.reqstore

import akka.NotUsed
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.MonadError
import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import io.hydrosphere.serving.gateway.config.ReqStoreConfig
import io.hydrosphere.serving.monitoring.monitoring.TraceData
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest

trait ReqStore[F[_], A] {
  def save(name: String, a: A): F[TraceData]
}

object ReqStore {

  import spray.json._
  import jsonCodecs._

  def create[F[_], A](cfg: ReqStoreConfig)(
    implicit
    F: Async[F],
    tbs: ToByteSource[A]
  ): F[ReqStore[F, A]] = {
    HttpClient.cachedPool(cfg.host, cfg.port, 200) map (create0(_))
  }

  def create0[F[_], A](client: HttpClient[F])(
    implicit
    F: MonadError[F, Throwable],
    tbs: ToByteSource[A]
  ): ReqStore[F, A] = {
    new ReqStore[F, A] {
      override def save(name: String, a: A): F[TraceData] = {
        val byteSource = tbs.asByteSource(a)

        val entity = HttpEntity.Default(
          ContentTypes.`application/octet-stream`,
          byteSource.size,
          byteSource.source
        )

        val httpReq = HttpRequest(
          HttpMethods.POST,
          Uri(s"/$name/put"),
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

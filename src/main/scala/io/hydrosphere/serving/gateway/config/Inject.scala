package io.hydrosphere.serving.gateway.config

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.headers.HttpOriginRange
import akka.stream.ActorMaterializer
import ch.megard.akka.http.cors.scaladsl.model.HttpHeaderRange
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import com.typesafe.config.ConfigFactory
import io.grpc.{Channel, ClientInterceptors, ManagedChannel, ManagedChannelBuilder}
import io.hydrosphere.serving.grpc.{AuthorityReplacerInterceptor, Headers}

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContextExecutor

object Inject {
  implicit val appConfig: Configuration = Configuration(ConfigFactory.load())

  implicit val system: ActorSystem = ActorSystem()
  implicit val executor: ExecutionContextExecutor = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val corsSettings: CorsSettings.Default = CorsSettings.Default(
    allowGenericHttpRequests = true,
    allowCredentials = true,
    allowedOrigins = HttpOriginRange.*,
    allowedHeaders = HttpHeaderRange.*,
    allowedMethods = Seq(GET, POST, HEAD, OPTIONS, DELETE),
    exposedHeaders = Seq.empty,
    maxAge = Some(30 * 60)
  )

  val builder=ManagedChannelBuilder
    .forAddress(appConfig.sidecarConfig.host, appConfig.sidecarConfig.port)
  builder.enableRetry()
  builder.usePlaintext()

  implicit val channel: Channel = ClientInterceptors
    .intercept(builder.build, new AuthorityReplacerInterceptor +: Headers.interceptors: _*)
}
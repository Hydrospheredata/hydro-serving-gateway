package io.hydrosphere.serving.gateway

import java.util.concurrent.TimeUnit

import cats.effect.IO
import io.hydrosphere.serving.gateway.discovery.application.DiscoveryService
import io.hydrosphere.serving.gateway.grpc.{GrpcApi, Prediction}
import io.hydrosphere.serving.gateway.http.HttpApi
import io.hydrosphere.serving.gateway.persistence.application.ApplicationInMemoryStorage
import io.hydrosphere.serving.gateway.service.application.ApplicationExecutionServiceImpl
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}


object Main extends App with Logging {

  implicit val contextShift = IO.contextShift(scala.concurrent.ExecutionContext.global)
  implicit val timer = IO.timer(scala.concurrent.ExecutionContext.global)

  logger.info("Hydroserving gateway service")
  try {

    import io.hydrosphere.serving.gateway.config.Inject._

    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

    logger.debug(s"Setting up GRPC sidecar channel")

    logger.debug(s"Initializing application storage")
    val applicationStorage = new ApplicationInMemoryStorage[IO]()

    logger.debug(s"Initializing application update service")
    val applicationUpdater = new DiscoveryService(
      appConfig.application.apiGateway,
      appConfig.application.grpc.deadline,
      applicationStorage
    )

    val grpcAlg = Prediction.create[IO](appConfig).unsafeRunSync()

    logger.debug("Initializing app execution service")
    val gatewayPredictionService = new ApplicationExecutionServiceImpl(
      appConfig.application,
      applicationStorage,
      grpcAlg
    )

    val grpcApi = new GrpcApi(appConfig.application, gatewayPredictionService, ec)

    val httpApi = new HttpApi(appConfig.application, gatewayPredictionService)

    sys addShutdownHook {
      logger.info("Terminating actor system")
      actorSystem.terminate()
      logger.info("Shutting down server")
      grpcApi.server.shutdown()
      try {
        grpcApi.server.awaitTermination(30, TimeUnit.SECONDS)
        Await.ready(httpApi.system.whenTerminated, Duration(30, TimeUnit.SECONDS))
      } catch {
        case e: Throwable =>
          logger.error("Error on terminate", e)
          sys.exit(1)
      }
    }
    logger.info("Initialization completed")
  } catch {
    case e: Throwable =>
      logger.error("Fatal error", e)
      sys.exit(1)
  }
}
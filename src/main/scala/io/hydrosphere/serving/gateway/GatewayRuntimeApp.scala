package io.hydrosphere.serving.gateway

import java.util.concurrent.TimeUnit

import io.hydrosphere.serving.gateway.grpc.GrpcApi
import io.hydrosphere.serving.gateway.http.HttpApi
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object GatewayRuntimeApp extends App with Logging {
  logger.info("Hydroserving gateway service")
  try {

    import io.hydrosphere.serving.gateway.config.Inject._

    val grpcApi = new GrpcApi(appConfig.application, gatewayPredictionService)

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

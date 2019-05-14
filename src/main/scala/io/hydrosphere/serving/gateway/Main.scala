package io.hydrosphere.serving.gateway

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import cats.effect._
import cats.syntax.functor._
import io.hydrosphere.serving.gateway.config.{ApplicationConfig, Configuration}
import io.hydrosphere.serving.gateway.discovery.application.DiscoveryService
import io.hydrosphere.serving.gateway.api.grpc.GrpcApi
import io.hydrosphere.serving.gateway.api.http.HttpApi
import io.hydrosphere.serving.gateway.execution.ExecutionService
import io.hydrosphere.serving.gateway.integrations.Monitoring
import io.hydrosphere.serving.gateway.persistence.application.ApplicationStorage
import io.hydrosphere.serving.gateway.persistence.servable.ServableStorage
import io.hydrosphere.serving.gateway.execution.application.{MonitoringClient, ResponseSelector}
import io.hydrosphere.serving.gateway.execution.grpc.{GrpcChannel, PredictionClient}
import io.hydrosphere.serving.gateway.util.{InstantClock, RandomNumberGenerator, UUIDGenerator}
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.config.Configurator

import scala.concurrent.ExecutionContext

// TODO: API for root cause (predict servable without shadow)
// TODO: API for replay (need to add timestamps and override if neccessary)
// TODO: merge circuit breaker changes
object Main extends IOApp with Logging {
  def application[F[_]](config: ApplicationConfig)(
    implicit F: ConcurrentEffect[F],
    timer: Timer[F],
    cs: ContextShift[F],
    ec: ExecutionContext,
    actorSystem: ActorSystem
  ) = {
    implicit val clock: Clock[F] = timer.clock
    implicit val uuidGen: UUIDGenerator[F] = UUIDGenerator[F]
    for {
      _ <- Resource.liftF(Logging.info[F](s"Hydroserving gateway service ${BuildInfo.version}"))
      _ <- Resource.liftF(Logging.debug[F](s"Initializing application storage"))

      channelCtor = GrpcChannel.grpc[F]
      clientCtor = PredictionClient.forEc(ec, channelCtor, config.grpc.deadline, config.grpc.maxMessageSize)


      reqStore <- Resource.liftF(MonitoringClient.mkReqStore(config.reqstore))
      monitoring = Monitoring.default(config.apiGateway, config.grpc.deadline, config.grpc.maxMessageSize)
      shadow = MonitoringClient.make(monitoring, reqStore)

      rng <- Resource.liftF(RandomNumberGenerator.default)
      responseSelector = ResponseSelector.randomSelector(F, rng)

      servableStorage <- Resource.liftF(ServableStorage.makeInMemory[F](clientCtor, shadow))
      appStorage <- Resource.liftF(ApplicationStorage.makeInMemory[F](servableStorage.getExecutor, shadow, responseSelector))
      appUpdater <- Resource.liftF(DiscoveryService.makeDefault[F](
        config.apiGateway,
        config.grpc.deadline,
        appStorage,
        servableStorage
      ))

      _ <- Resource.liftF(Logging.debug[F]("Initializing app execution service"))
      predictionService <- Resource.liftF(ExecutionService.makeDefault(
        appStorage,
        servableStorage
      ))

      grpcApi <- GrpcApi.makeAsResource(config, predictionService, ec)
      _ <- Resource.liftF(Logging.info[F]("Initialized GRPC API"))

      httpApi <- Resource.liftF(F.delay {
        implicit val mat: ActorMaterializer = ActorMaterializer()
        new HttpApi(config, predictionService, appStorage, servableStorage)
      })
      _ <- Resource.liftF(Logging.info[F]("Initialized HTTP API"))

    } yield grpcApi -> httpApi
  }


  override def run(args: List[String]): IO[ExitCode] = IO.suspend {
    implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
    val appResource = for {
      actorSystem <- Resource.make(IO(ActorSystem("hydroserving-gateway")))(x => IO.fromFuture(IO(x.terminate())).as(()))
      _ <- Resource.liftF(Logging.info[IO]("Reading configuration"))
      appConfig <- Resource.liftF(Configuration.load[IO])
      _ <- Resource.liftF(Logging.info[IO](s"Configuration: $appConfig"))
      res <- {
        implicit val as = actorSystem
        application[IO](appConfig.application)
      }

    } yield res

    appResource.use {
      case (grpcApi, httpApi) =>
        for {
          _ <- httpApi.start()
          _ <- grpcApi.start()
          _ <- Logging.info[IO]("Initialization completed")
          _ <- IO.never
        } yield ExitCode.Success
    }.guaranteeCase {
      case ExitCase.Completed =>
        Logging.warn[IO]("Exiting application normally")
      case ExitCase.Canceled =>
        Logging.warn[IO]("Application is cancelled")
      case ExitCase.Error(err) =>
        Logging.error[IO]("Application failure", err)
    }.map { x =>
      sys.addShutdownHook {
        LogManager.getContext match {
          case context: LoggerContext =>
            logger.debug("Shutting down log4j2")
            Configurator.shutdown(context)
          case _ => logger.warn("Unable to shutdown log4j2")
        }
      }
      x
    }
  }
}
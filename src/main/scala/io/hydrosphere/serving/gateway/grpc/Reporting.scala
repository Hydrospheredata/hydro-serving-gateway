package io.hydrosphere.serving.gateway.grpc

import cats.{Applicative, Functor}
import cats.data.NonEmptyList
import cats.effect.{IO, LiftIO}
import cats.syntax.functor._
import io.grpc.Channel
import io.hydrosphere.serving.gateway.config.{ApplicationConfig, Configuration}
import io.hydrosphere.serving.gateway.service.application.ExecutionUnit
import io.hydrosphere.serving.grpc.AuthorityReplacerInterceptor
import io.hydrosphere.serving.monitoring.monitoring.{ExecutionInformation, ExecutionMetadata, MonitoringServiceGrpc}
import io.hydrosphere.serving.monitoring.monitoring.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.monitoring.MonitoringServiceGrpc.MonitoringServiceStub
import io.hydrosphere.serving.profiler.profiler.DataProfilerServiceGrpc
import io.hydrosphere.serving.tensorflow.api.predict.PredictRequest

import scala.concurrent.Future
import scala.concurrent.duration.Duration

trait Reporter[F[_]] {
  def send(execInfo: ExecutionInformation): F[Unit]
}

object Reporter {

  def apply[F[_]](f: ExecutionInformation => F[Unit]): Reporter[F] =
    new Reporter[F] {
      def send(execInfo: ExecutionInformation): F[Unit] = f(execInfo)
    }

  def fromFuture[F[_]: Functor, A](f: ExecutionInformation => Future[A])(implicit F: LiftIO[F]): Reporter[F] =
    Reporter(info => F.liftIO(IO.fromFuture(IO(f(info)))).void)


  def profilingGrpc[F[_]: Functor: LiftIO](
    deadline: Duration,
    destination: String,
    grpcClient: DataProfilerServiceGrpc.DataProfilerServiceStub
  ): Reporter[F] = {
    Reporter.fromFuture(info => {
      grpcClient
        .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, destination)
        .withDeadlineAfter(deadline.length, deadline.unit)
        .analyze(info)
    })
  }

}

object Reporters {

  object Monitoring {

    def envoyBased[F[_]: Functor: LiftIO](channel: Channel, appConf: ApplicationConfig): Reporter[F] = {
      val stub = MonitoringServiceGrpc.stub(channel)
      monitoringGrpc(appConf.grpc.deadline, appConf.monitoringDestination, stub)
    }

    def monitoringGrpc[F[_] : Functor : LiftIO](
      deadline: Duration,
      destination: String,
      grpcClient: MonitoringServiceStub
    ): Reporter[F] = {
      Reporter.fromFuture(info => {
        grpcClient
          .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, destination)
          .withDeadlineAfter(deadline.length, deadline.unit)
          .analyze(info)
      })
    }

  }

  object Profiling {

    def envoyBased[F[_]: Functor: LiftIO](channel: Channel, appConf: ApplicationConfig): Reporter[F] = {
      val stub = DataProfilerServiceGrpc.stub(channel)
      profilingGrpc(appConf.grpc.deadline, appConf.profilingDestination, stub)
    }

    def profilingGrpc[F[_]: Functor: LiftIO](
      deadline: Duration,
      destination: String,
      grpcClient: DataProfilerServiceGrpc.DataProfilerServiceStub
    ): Reporter[F] = {
      Reporter.fromFuture(info => {
        grpcClient
          .withOption(AuthorityReplacerInterceptor.DESTINATION_KEY, destination)
          .withDeadlineAfter(deadline.length, deadline.unit)
          .analyze(info)
      })
    }
  }
}


trait Reporting[F[_]] {
  def report(request: PredictRequest, eu: ExecutionUnit, value: ResponseOrError): F[Unit]
}

object Reporting {

  def default[F[_]: Applicative: LiftIO](channel: Channel, appConf: ApplicationConfig): Reporting[F] = {
    val monitoring = Reporters.Monitoring.envoyBased(channel, appConf)
    val dataProfiler = Reporters.Profiling.envoyBased(channel, appConf)

    create0(NonEmptyList.of(monitoring, dataProfiler))
  }

  // todo ContextShift + special ExecutionContext
  def create0[F[_]: Applicative](reporters: NonEmptyList[Reporter[F]]): Reporting[F] = {
    new Reporting[F] {
      def report(request: PredictRequest, eu: ExecutionUnit, value: ResponseOrError): F[Unit] = {
        val info = ExecutionInformation(
          metadata = Option(ExecutionMetadata(
            applicationId = eu.stageInfo.applicationId,
            stageId = eu.stageInfo.stageId,
            modelVersionId = eu.stageInfo.modelVersionId.getOrElse(-1),
            signatureName = eu.stageInfo.signatureName,
            applicationRequestId = eu.stageInfo.applicationRequestId.getOrElse(""),
            requestId = eu.stageInfo.applicationRequestId.getOrElse(""), //todo fetch from response,
            applicationNamespace = eu.stageInfo.applicationNamespace.getOrElse(""),
            dataTypes = eu.stageInfo.dataProfileFields
          )),
          request = Option(request),
          responseOrError = value
        )

        reporters.traverse(r => r.send(info)).void
      }
    }
  }

  def noop[F[_]](implicit F: Applicative[F]): Reporting[F] = (_, _, _) => F.pure(())
}


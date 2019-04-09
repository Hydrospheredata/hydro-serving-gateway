package io.hydrosphere.serving.gateway.grpc

import cats.implicits._
import cats.effect._
import cats.effect.implicits._
import io.hydrosphere.serving.gateway.config.{Configuration, ReqStoreConfig}
import io.hydrosphere.serving.gateway.grpc.reqstore.ReqStore
import io.hydrosphere.serving.gateway.service.application.{ExecutionMeta, ExecutionUnit}
import io.hydrosphere.serving.gateway.util.CircuitBreaker
import io.hydrosphere.serving.monitoring.api.ExecutionInformation
import io.hydrosphere.serving.monitoring.api.ExecutionInformation.ResponseOrError
import io.hydrosphere.serving.monitoring.metadata.{ExecutionError, ExecutionMetadata, TraceData}
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration._

trait Prediction[F[_]] {

  def predict(unit: ExecutionUnit, request: PredictRequest): F[PredictResponse]

}

object Prediction extends Logging {

  type ServingReqStore[F[_]] = ReqStore[F, (PredictRequest, ResponseOrError)]

  def create[F[_]](conf: Configuration)(implicit F: ConcurrentEffect[F], timer: Timer[F]): F[Prediction[F]] = {
    for {
      maybeReqStore   <- mkReqStore(conf)
      maybeMonitoring =  mkMonitoring(conf)
    } yield {
      
      val predictF = (eu: ExecutionUnit, req: PredictRequest) => {
        F.liftIO(IO.fromFuture(IO(eu.client.send(req))))
      }
      
      val saveF: (String, PredictRequest, ResponseOrError) => F[Option[TraceData]] = maybeReqStore match {
        case Some(reqstore) =>
          val listener = (st: CircuitBreaker.Status) => F.delay(logger.info(s"Restore circuit breaker status was changed: $st"))
          val cb = CircuitBreaker[F](3 seconds, 5, 30 seconds)(listener)
          (id: String, req: PredictRequest, resp: ResponseOrError) =>
            cb.use(reqstore.save(id, (req, resp)).attempt.map(_.toOption))
        case None =>
          (id: String, req: PredictRequest, resp: ResponseOrError) => F.pure(None)
      }
      
      val monitoringF = maybeMonitoring match {
        case Some(monitoring) =>
          (req: PredictRequest, resp: ResponseOrError, meta: ExecutionMetadata) => {
            val info = ExecutionInformation(req.some, meta.some, resp)
            monitoring.send(info).attempt.void
          }
        case None =>
          (_: PredictRequest, _: ResponseOrError, _: ExecutionMetadata) => {
            F.pure(())
          }
      }
      
      new Prediction[F] {
        override def predict(unit: ExecutionUnit, req: PredictRequest): F[PredictResponse] = {
          val flow = for {
            out       <- predictF(unit, req)
            respOrErr =  Pbf.responseOrError(out.result)
            traceData <- saveF(out.modelVersionId.toString, req, respOrErr)
            execMeta  =  Pbf.execMeta(unit.meta, out.modelVersionId, traceData)
            _         <- monitoringF(req, respOrErr, execMeta).start
          } yield {
            (out.result, traceData) match {
              case (Right(resp), Some(data)) => Right(resp.withTraceData(data))
              case _ => out.result
            }
          }
          
          flow.rethrow
        }
      }
      
    }
  }
  
  private def mkReqStore[F[_]](conf: Configuration)(implicit F: Concurrent[F]): F[Option[ServingReqStore[F]]] = {
    if (conf.application.reqstore.enabled) {
      ReqStore.create[F, (PredictRequest, ResponseOrError)](conf.application.reqstore)
        .map(_.some)
    } else {
      F.pure(None)
    }
  }
  
  private def mkMonitoring[F[_]](conf: Configuration)(implicit F: Async[F]): Option[Monitoring[F]] = {
    if (conf.application.shadowingOn) {
      Monitoring.default(conf.application.apiGateway, conf.application.grpc.deadline).some
    } else {
      None
    }
  }
  
  private object Pbf {
  
    def execMeta(
      meta: ExecutionMeta,
      modelVersionId: Long,
      traceData: Option[TraceData]
    ): ExecutionMetadata = {
      ExecutionMetadata(
        applicationId = meta.applicationId,
        stageId = meta.stageId,
        modelVersionId = modelVersionId,
        signatureName = meta.signatureName,
        applicationRequestId = meta.applicationRequestId.getOrElse(""),
        requestId = meta.applicationRequestId.getOrElse(""),
        applicationNamespace = meta.applicationNamespace.getOrElse(""),
        traceData = traceData
      )
    }
  
    def responseOrError(poe: Either[Throwable, PredictResponse]): ResponseOrError = {
      poe match {
        case Left(err) => ResponseOrError.Error(ExecutionError(err.getMessage))
        case Right(v) => ResponseOrError.Response(v)
      }
    }
  }

}

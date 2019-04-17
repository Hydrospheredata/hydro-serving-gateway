package io.hydrosphere.serving.gateway.persistence.application

import cats._
import cats.data.NonEmptyList
import cats.implicits._
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import io.hydrosphere.serving.tensorflow.TensorShape
import io.hydrosphere.serving.tensorflow.api.predict.{PredictRequest, PredictResponse}
import io.hydrosphere.serving.tensorflow.api.prediction_service.PredictionServiceGrpc
import io.hydrosphere.serving.tensorflow.tensor.{Int64Tensor, TensorProto}
import io.hydrosphere.serving.tensorflow.types.DataType

import scala.collection.immutable.TreeMap
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.Random

final case class PredictOut(
  result: Either[Throwable, PredictResponse],
  latency: Long,
  modelVersionId: Long
)



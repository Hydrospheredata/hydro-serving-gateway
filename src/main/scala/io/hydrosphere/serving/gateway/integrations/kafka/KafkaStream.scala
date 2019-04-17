package io.hydrosphere.serving.gateway.integrations.kafka

import io.hydrosphere.serving.gateway.persistence.application.StoredApplication
import io.hydrosphere.serving.manager.grpc.applications.Application
import org.apache.kafka.streams.kstream.{KStream, ValueMapper, ValueTransformer, ValueTransformerSupplier}
import org.apache.logging.log4j.scala.Logging

object KafkaStream {
  def apply[K, V](kStream: KStream[K, V]) = new KafkaStream[K, V](kStream)
}

class KafkaStream[K, V](val underlying: KStream[K, V]) extends Stream[K, V] with Logging {

  override def mapV[V1](f: V => V1): KafkaStream[K, V1] = KafkaStream {
    val mapper: ValueMapper[V, V1] = (value: V) => f(value)
    underlying.mapValues(mapper)
  }

  override def filterV(f: V => Boolean): KafkaStream[K, V] = KafkaStream {
    underlying.filter((key: K, value: V) => f(value))
  }

  override def transformV[VR](getTransformer:() => ValueTransformer[V, VR]): KafkaStream[K, VR] = KafkaStream {
    underlying.transformValues(new ValueTransformerSupplier[V, VR]{
      override def get() = getTransformer()
    })
  }


  def branchV(success: V => Boolean, failure: V => Boolean): DoubleStream[K, V] = {
    val array = underlying.branch(
      (key: K, value: V) => success(value),
      (key: K, value: V) => failure(value)
    )

    val successResult = array(0)
    val failureDesult = array(1)

    (KafkaStream(successResult).withLog(v => logger.debug(s"message handled: $v")),
      KafkaStream(failureDesult).withLog(v => logger.error(s"message failed: $v"))
    )
  }

  override def to(topicName: String): Unit = underlying.to(topicName)

}


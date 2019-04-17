package io.hydrosphere.serving.gateway.integrations.kafka

import java.util.Properties

import cats.effect.{Async, Resource}
import io.hydrosphere.serving.gateway.config.KafkaConfig
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.Serializer

object Producer {
  def kafkaProducer[F[_], K, V](
    config: KafkaConfig,
    keySerializer: Class[_ <: Serializer[K]],
    valSerializer: Class[_ <: Serializer[V]]
  )(implicit F: Async[F]): Resource[F, Producer[F, K, V]] = {
    val producer = F.delay {
      val hostAndPort = s"${config.advertisedHost}:${config.advertisedPort}"
      val props = new Properties()
      props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, hostAndPort)
      props.put("key.serializer", keySerializer.getName)
      props.put("value.serializer", valSerializer.getName)
      new Producer[F, K, V](new KafkaProducer[K, V](props))
    }
    Resource.make(producer)(_.close())
  }
}

class Producer[F[_], K, V](underlying: KafkaProducer[K, V])(implicit F: Async[F]) {
  def send(topic: String, key: K, message: V): F[RecordMetadata] = {
    val record = new ProducerRecord(topic, key, message)
    send(record)
  }

  def send(topic: String, message: V): F[RecordMetadata] = {
    val record = new ProducerRecord[K, V](topic, message)
    send(record)
  }

  def send(record: ProducerRecord[K, V]): F[RecordMetadata] = {
    F.async[RecordMetadata] { cb =>
      underlying.send(record, kafkaCallback(cb))
    }
  }

  def close(): F[Unit] = {
    F.delay(underlying.close())
  }

  def kafkaCallback(cb: Either[Throwable, RecordMetadata] => Unit): Callback = {
    (metadata: RecordMetadata, exception: Exception) => {
      Option(exception) match {
        case Some(ex) => cb(Left(ex))
        case None => cb(Right(metadata))
      }
    }
  }
}
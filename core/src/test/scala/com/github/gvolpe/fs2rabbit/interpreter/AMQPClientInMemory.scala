/*
 * Copyright 2017 Fs2 Rabbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.gvolpe.fs2rabbit.interpreter

import cats.effect.{ContextShift, IO}
import cats.effect.concurrent.Ref
import cats.syntax.functor._
import com.github.gvolpe.fs2rabbit.algebra.{AMQPClient, AMQPInternals}
import com.github.gvolpe.fs2rabbit.arguments.Arguments
import com.github.gvolpe.fs2rabbit.config.declaration.{DeclarationExchangeConfig, DeclarationQueueConfig}
import com.github.gvolpe.fs2rabbit.config.deletion.DeletionQueueConfig
import com.github.gvolpe.fs2rabbit.config.{Fs2RabbitConfig, deletion}
import com.github.gvolpe.fs2rabbit.model
import com.github.gvolpe.fs2rabbit.model.AckResult.{Ack, NAck}
import com.github.gvolpe.fs2rabbit.model._
import com.rabbitmq.client.Channel
import fs2.Stream
import fs2.concurrent.Queue

class AMQPClientInMemory(queues: Ref[IO, Set[QueueName]],
                         exchanges: Ref[IO, Set[ExchangeName]],
                         binds: Ref[IO, Map[String, ExchangeName]],
                         ref: Ref[IO, AMQPInternals[IO]],
                         publishingQ: Queue[IO, Either[Throwable, AmqpEnvelope]],
                         listenerQ: Queue[IO, PublishReturn],
                         ackerQ: Queue[IO, AckResult],
                         config: Fs2RabbitConfig)(implicit cs: ContextShift[IO])
    extends AMQPClient[Stream[IO, ?], IO] {

  private def raiseError[A](message: String): Stream[IO, A] =
    Stream.raiseError[IO](new java.io.IOException(message))

  override def basicAck(channel: Channel, tag: model.DeliveryTag, multiple: Boolean): Stream[IO, Unit] =
    Stream.eval(ackerQ.enqueue1(Ack(tag)))

  override def basicNack(
      channel: Channel,
      tag: model.DeliveryTag,
      multiple: Boolean,
      requeue: Boolean
  ): Stream[IO, Unit] = {
    // Imitating the RabbitMQ behavior
    val envelope = AmqpEnvelope(DeliveryTag(1), "requeued msg", AmqpProperties.empty)
    for {
      _ <- Stream.eval(ackerQ.enqueue1(NAck(tag)))
      _ <- if (config.requeueOnNack) Stream.eval(publishingQ.enqueue1(Right(envelope)))
          else Stream.eval(IO.unit)
    } yield ()
  }

  override def basicQos(
      channel: Channel,
      basicQos: model.BasicQos
  ): Stream[IO, Unit] = Stream.eval(IO.unit)

  override def basicConsume(channel: Channel,
                            queueName: model.QueueName,
                            autoAck: Boolean,
                            consumerTag: String,
                            noLocal: Boolean,
                            exclusive: Boolean,
                            args: Arguments)(internals: AMQPInternals[IO]): Stream[IO, String] = {
    val ifError =
      raiseError[String](s"Queue ${queueName.value} does not exist!")

    Stream
      .eval(queues.get)
      .flatMap(_.find(_.value == queueName.value).fold(ifError) { _ =>
        Stream.eval(ref.set(internals)).map(_ => "dequeue1 happens in AckerConsumerProgram.createConsumer")
      })
  }

  override def basicPublish(
      channel: Channel,
      exchangeName: model.ExchangeName,
      routingKey: model.RoutingKey,
      msg: model.AmqpMessage[String]
  ): Stream[IO, Unit] = {
    val envelope = AmqpEnvelope(DeliveryTag(1), msg.payload, msg.properties)
    Stream.eval(publishingQ.enqueue1(Right(envelope)))
  }

  override def basicPublishWithFlag(
      channel: Channel,
      exchangeName: ExchangeName,
      routingKey: RoutingKey,
      flag: PublishingFlag,
      msg: AmqpMessage[String]
  ): Stream[IO, Unit] = {
    val ifNoBind = {
      val publishReturn =
        PublishReturn(
          ReplyCode(123),
          ReplyText("test"),
          exchangeName,
          routingKey,
          msg.properties,
          AmqpBody(msg.payload)
        )
      Stream.eval(listenerQ.enqueue1(publishReturn))
    }

    Stream
      .eval(binds.get)
      .flatMap(_.get(routingKey.value).fold(ifNoBind) { _ =>
        basicPublish(channel, exchangeName, routingKey, msg)
      })
  }

  override def addPublishingListener(
      channel: Channel,
      listener: PublishingListener[IO]
  ): Stream[IO, Unit] =
    Stream.eval(listenerQ.dequeue1.flatMap(listener).start.void)

  override def clearPublishingListeners(
      channel: Channel
  ): Stream[IO, Unit] = Stream.eval(IO.unit)

  override def deleteQueue(
      channel: Channel,
      config: DeletionQueueConfig
  ): Stream[IO, Unit] =
    Stream.eval(queues.update(_ - config.queueName))

  override def deleteQueueNoWait(
      channel: Channel,
      config: DeletionQueueConfig
  ): Stream[IO, Unit] =
    deleteQueue(channel, config)

  override def deleteExchange(
      channel: Channel,
      config: deletion.DeletionExchangeConfig
  ): Stream[IO, Unit] =
    Stream
      .eval(exchanges.get)
      .flatMap(
        _.find(_ == config.exchangeName).fold(raiseError[Unit](s"Exchange ${config.exchangeName} does not exist")) {
          exchange =>
            Stream.eval(exchanges.update(_ - exchange))
        })

  override def deleteExchangeNoWait(
      channel: Channel,
      config: deletion.DeletionExchangeConfig
  ): Stream[IO, Unit] =
    deleteExchange(channel, config)

  override def bindQueue(
      channel: Channel,
      queueName: model.QueueName,
      exchangeName: model.ExchangeName,
      routingKey: model.RoutingKey
  ): Stream[IO, Unit] =
    Stream.eval(binds.update(_.updated(routingKey.value, exchangeName)))

  override def bindQueue(
      channel: Channel,
      queueName: model.QueueName,
      exchangeName: model.ExchangeName,
      routingKey: model.RoutingKey,
      args: model.QueueBindingArgs
  ): Stream[IO, Unit] =
    bindQueue(channel, queueName, exchangeName, routingKey)

  override def bindQueueNoWait(
      channel: Channel,
      queueName: model.QueueName,
      exchangeName: model.ExchangeName,
      routingKey: model.RoutingKey,
      args: model.QueueBindingArgs
  ): Stream[IO, Unit] =
    bindQueue(channel, queueName, exchangeName, routingKey)

  override def unbindQueue(
      channel: Channel,
      queueName: model.QueueName,
      exchangeName: model.ExchangeName,
      routingKey: model.RoutingKey
  ): Stream[IO, Unit] =
    Stream.eval(binds.update(_ - routingKey.value))

  override def unbindQueue(
      channel: Channel,
      queueName: model.QueueName,
      exchangeName: model.ExchangeName,
      routingKey: model.RoutingKey,
      args: QueueUnbindArgs
  ): Stream[IO, Unit] =
    unbindQueue(channel, queueName, exchangeName, routingKey)

  override def bindExchange(
      channel: Channel,
      destination: model.ExchangeName,
      source: model.ExchangeName,
      routingKey: model.RoutingKey,
      args: model.ExchangeBindingArgs
  ): Stream[IO, Unit] = Stream.eval(IO.unit)

  override def bindExchangeNoWait(
      channel: Channel,
      destination: ExchangeName,
      source: ExchangeName,
      routingKey: RoutingKey,
      args: ExchangeBindingArgs
  ): Stream[IO, Unit] = Stream.eval(IO.unit)

  override def unbindExchange(
      channel: Channel,
      destination: ExchangeName,
      source: ExchangeName,
      routingKey: RoutingKey,
      args: ExchangeUnbindArgs
  ): Stream[IO, Unit] = Stream.eval(IO.unit)

  override def declareExchange(
      channel: Channel,
      exchangeConfig: DeclarationExchangeConfig
  ): Stream[IO, Unit] =
    declareExchangePassive(channel, exchangeConfig.exchangeName)

  override def declareExchangeNoWait(
      channel: Channel,
      exchangeConfig: DeclarationExchangeConfig
  ): Stream[IO, Unit] =
    declareExchangePassive(channel, exchangeConfig.exchangeName)

  override def declareExchangePassive(
      channel: Channel,
      exchangeName: ExchangeName
  ): Stream[IO, Unit] =
    Stream.eval(exchanges.update(_ + exchangeName))

  override def declareQueue(
      channel: Channel,
      queueConfig: DeclarationQueueConfig
  ): Stream[IO, Unit] =
    declareQueuePassive(channel, queueConfig.queueName)

  override def declareQueueNoWait(
      channel: Channel,
      queueConfig: DeclarationQueueConfig
  ): Stream[IO, Unit] =
    declareQueuePassive(channel, queueConfig.queueName)

  override def declareQueuePassive(
      channel: Channel,
      queueName: QueueName
  ): Stream[IO, Unit] =
    Stream.eval(queues.update(_ + queueName))

}

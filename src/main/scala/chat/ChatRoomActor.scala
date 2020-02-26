/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package chat

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.pattern.gracefulStop
import chat.EventConstants._
import com.redis._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

object ChatRoomActor {
  case object Join
  case object Leave
  case object Block
  case class RegUser(userID: Int, nickName: String)
  case class SpecialTargetResponse(message: String)
  case class ChatMessageToLocalUsers(message: String)
  case class ChatMessage(message: String)
}

/**
  * The actor that is created for each chat room. Various kinds of messages
  * that represent chat room-related events such as comment sending or delivery
  * termination are all routed to this actor.
  *
  * ChatRoomActor is in charge of
  * publishing comments to Redis or storing comments in Redis for comment
  * synchronization between servers (more details on the following section).
  *
  * It also passes to UserActor all messages destined to the clients.
  *
  * @param chatRoomID ChatRoom Unique Number
  */
class ChatRoomActor(chatRoomID: Int, envType: String) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher
  implicit val system = context.system

  val prefix = system.settings.config.getString("akka.environment.pubsub-channel.prefix")
  val postfix = system.settings.config.getString("akka.environment.pubsub-channel.postfix")
  val chatRoomName = setChatRoomName(envType, prefix, postfix)

  val recvTimeout = system.settings.config.getInt(s"akka.environment.${envType}.chatroom-receive-timeout")
  val redisIp = system.settings.config.getString(s"akka.environment.${envType}.redis-ip")
  val redisPort = system.settings.config.getInt(s"akka.environment.${envType}.redis-port")
  var s = safeConnect(redisIp, redisPort)
  var p = safeConnect(redisIp, redisPort)
//  var p = new RedisClient(redisIp, redisPort)

  import ChatRoomActor._
  private var failover: Boolean = true
  private var users: Set[ActorRef] = Set.empty
  private var member: Int = 0
  private var guest: Int = 0

  context.setReceiveTimeout(Duration.create(recvTimeout, TimeUnit.SECONDS))

  def setChatRoomName(env: String, prefix: String, postfix: String): String = {
    env match {
      case "live" | "standby" | "development" => prefix + self.path.name + postfix
      case _ => prefix + "unknown." + self.path.name + postfix
    }
  }

  def safeConnect(redisIp:String, redisPort:Int): Try[RedisClient] = {
    try {
      Success(new RedisClient(redisIp, redisPort))
    } catch {
      case x: Exception =>
        log.info("Can NOT connect to redis. It caused by " + x.toString)
        Failure(x)
    }
  }
  /**
    * This function is used for connect to redis. If redis is dead, we'll retry until redis is up.
    */
  def connectToRedis(): Unit = {
    if (failover) {
//      try {
//        if (!s.connected) {
//          rt)
//        }s = new RedisClient(redisIp, redisPo
//        if (!p.connected) {
//          p = new RedisClient(redisIp, redisPort)
//        }
//
//        subscribe()
//      } catch {
//        case x: Exception =>
//          log.info("Retry to connect redis. It caused by " + x)
//          connectToRedis()
//      } finally {
//        log.info("## Redis connection has established.")
//      }

      s match {
        case Success(redisClient) if !redisClient.connected =>
          s = safeConnect(redisIp, redisPort)
        case _ => log.info("Sub redis is not connected")
      }
      p match {
        case Success(redisClient) if !redisClient.connected =>
          p = safeConnect(redisIp, redisPort)
        case _ => log.info("Pub redis is not connected")
      }
      if (s.isFailure || p.isFailure) {
        log.info(s"Retry to connect redis. It caused by sub failure=${s.isFailure} or pub failure=${p.isFailure}")
        // need reconnection times control and retries
//         connectToRedis()
        throw new Exception("Redis connection failure")
      }
      else {
        subscribe(s.get, p.get)
        log.info("Redis connection established.")
      }

    } else {
//      if (p.connected && s.connected) {
      if (p.isSuccess && p.get.connected && s.isSuccess && s.get.connected) {
        //s.unsubscribe(chatRoomName)
        p.get.disconnect
        s.get.disconnect
        log.info(s"[#$chatRoomID] Retry, Redis Disconnected.")
      } else {
        log.info(s"[#$chatRoomID]  => Redis Disconnected.")
      }
    }
  }

  def subscribe(s:RedisClient, p:RedisClient): Unit = {
    s.subscribe(chatRoomName) {
      case S(channel, no) => log.info("subscribed to " + channel + " and count = " + no)
      case U(channel, no) => log.info("unsubscribed from " + channel + " and count = " + no)
      case E(exception) =>
        p.disconnect
        s.disconnect
        if (exception.toString.equals("com.redis.RedisConnectionException: Connection dropped ..")) {
          log.error(exception + ", #1 Fatal error caught at Redis subscribe(). :" + chatRoomName)
          connectToRedis()
        } else {
          log.error(exception + ", #2 Fatal error caught at Redis subscribe(). :" + chatRoomName)
        }

      case M(channel, msg) =>
        broadcast(msg)
    }
  }

  def broadcast(message: String): Unit = {
    users.foreach(_ ! ChatRoomActor.ChatMessage(message))
  }

  def updateIncrRoomUser(isGuest: Boolean, firstJoin: Boolean, joinUser: ActorRef): Any = {
    if (firstJoin) {
      users += joinUser
      environment.aggregator ! UpdateChatCount(chatRoomID, users.size, -1, -1)

      // we also would like to remove the user when its actor is stopped
      context.watch(joinUser)
    } else {
      if (isGuest) {
        guest += 1
      } else {
        member+= 1
      }

      environment.aggregator ! UpdateChatCount(chatRoomID, users.size, member, guest)
    }
  }

  def updateDecrRoomUser(isGuest: Boolean, isJoin: Boolean, termUser: ActorRef): Unit = {
    if (isJoin) {
      if (isGuest) {
        guest -= 1
      } else {
        member-= 1
      }
    }

    users -= termUser
    environment.aggregator ! UpdateChatCount(chatRoomID, users.size, member, guest)

    if (users.isEmpty) {
      destroyChatRoom()
    }
  }

  override def preStart(): Unit = {
    log.info(s"[#$chatRoomID] actor has created. ${chatRoomName}")
    connectToRedis()
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.info(s"[ChatRoomActor#$chatRoomID] Restarting ... ${chatRoomName}")
    preStart()
  }

  override def postRestart(reason: Throwable): Unit = {
    log.info(s"[ChatRoomActor#$chatRoomID] Restarted ... ${chatRoomName}")
  }

  override def postStop(): Unit = {
    log.info(s"[ChatRoomActor#$chatRoomID] Down ... ${chatRoomName}")
  }

  def destroyChatRoom():Unit = {
    failover = false
    p.get.disconnect
    s.get.disconnect

    ChatRooms.removeChatRoom(chatRoomID)
    environment.aggregator ! RemoveChatRoom(chatRoomID)

    gracefulStop(self, Duration.create(5, TimeUnit.SECONDS))
    log.info(s"[ChatRoomActor#$chatRoomID] ChatRoomActor PoisonPill")
  }

  /**
    * Receives messages from Server, and synchronizes messages with others.
    * @return nothing
    */
  def receive = {
    case ReceiveTimeout =>
      log.info("I'm on idle status over 3 hours. Kill myself.")
      destroyChatRoom()

    case Block =>
      log.info(s"[#$chatRoomID] received Block Event:" + chatRoomName)

    case Join =>
      log.info(s"[ChatRoomActor#$chatRoomID] received Join Event:" + chatRoomName)
      updateIncrRoomUser(false,true, sender())

    case Leave =>
      log.info(s"[#$chatRoomID] receive Leave Event:" + chatRoomName)
      updateDecrRoomUser(false, false, sender())

    case TermChatUser(chatRoomID, is_guest, uID, nick) =>
      log.info(s"[#$chatRoomID] received TermChatUser Event:" + chatRoomName)
      updateDecrRoomUser(is_guest, true, sender())

    case RegUser(userID, nickName) => // todo : userinfo
      log.info(s"[#$chatRoomID] received Registry User Event:" + chatRoomName)
      updateIncrRoomUser(false,false,null)

    case SpecialTargetResponse(message) =>
      log.info(s"[#$chatRoomID] received SpecialTargetResponse Event:" + chatRoomName)
      updateIncrRoomUser(true,false,null)

      sender() ! ChatRoomActor.ChatMessage(message)

    case msg: ChatMessageToLocalUsers =>
      broadcast(msg.message)

    case msg: ChatMessage =>
      // publish message to all chatRoomActor that subscribes same chatRoomName
      log.info(s"[#$chatRoomID] publish message to chanel: " + chatRoomName)
      log.info(s"messageLog ${msg.message}")

      // original message should be logged
      if (p.get.connected && s.get.connected)
        p.get.publish(chatRoomName, msg.message)

    case Terminated(user) => // for UserActor
      log.info(s"[#$chatRoomID] receive Terminated Event:" + chatRoomName)
  }
}

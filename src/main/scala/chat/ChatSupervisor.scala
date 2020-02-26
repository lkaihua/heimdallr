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

import akka.actor.SupervisorStrategy._
import akka.actor._
import chat.EventConstants._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Parent actor of multiple chatroom actors.
  * @param envType
  */
class ChatSupervisor(envType: String) extends Actor with ActorLogging {
  implicit val system = context.system
  implicit val executionContext: ExecutionContext = context.dispatcher

  override val supervisorStrategy =
    OneForOneStrategy(
      maxNrOfRetries = 10,
      withinTimeRange = 1 minute,
      loggingEnabled = true) {
      case x: Exception =>
        log.info("FIXME: ChatSupervisor => " + x.toString)
        Resume
      case t =>
        super.supervisorStrategy.decider.applyOrElse( t, (_:Any) => Escalate )
    }

  override def preStart(): Unit = {
    log.info("ChatSupervisor started")
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.info( reason.toString )
    preStart()
  }

  override def postRestart(reason: Throwable): Unit = {
    log.info( reason.toString )
  }

  override def postStop(): Unit = {}

  /**
    * @param hostName, port
    */
  def setNodeInfor(hostName: String, port: Int): Unit = {
    ChatRooms.hostName = hostName
    ChatRooms.port = port
  }

  /**
    * @param number chatroom ID
    * @return the reference of chatRoomActor of given number
    */
  def getChatRoomActorRef(number:Int): ActorRef = {
    //create or get ChatRoom as an ActorRef
    this.synchronized {
      ChatRooms.chatRooms.getOrElse(number, createNewChatRoom(number))
    }
  }

  /**
    * Creates new chatroom actor and adds chatRooms map
    *
    * @param number chatroom ID
    * @return the reference of newly created chatRoomActor
    */
  def createNewChatRoom(number: Int): ActorRef = {
    //creates new ChatRoomActor and returns as an ActorRef
    log.info(s"createNewChatRoom $number")

    val chatroom = context.actorOf(Props(classOf[ChatRoomActor], number, envType), s"chatroom${number}")
    ChatRooms.chatRooms += number -> chatroom
    chatroom
  }

  override def receive: Receive = {
    case RegNodeInfor(hostName, port) =>
      setNodeInfor(hostName, port)

    case CreateChatRoom(chatRoomID) =>
      log.info(s"CreateChatRoom $chatRoomID")
      getChatRoomActorRef(chatRoomID)

    case RegChatUser(chatRoomID, userActor) =>
      userActor ! JoinRoom(getChatRoomActorRef(chatRoomID))

    case RegProps(props, name) =>
      context.actorOf(props, name)

    case HeimdallrError =>
      throw new ArithmeticException()

    case HeimdallrChatStatus =>
      log.info( "Heimdallr ChatSupervisor Running ..." )

    case Terminated(user) =>
      log.info("Receive Terminated Event of ChatRoomActor")

    case x =>
      log.warning("ChatSupervisor Unknown message : " + x)
  }
}


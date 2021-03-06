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

import akka.actor._
import akka.actor.SupervisorStrategy._
import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.RemoteAddress
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits._
import java.net._
import EventConstants._

/**
  * The chat service. This routes the messages incoming from outside to the destination user actors.
  * @param chatSuper
  */
class ChatService(chatSuper: ActorRef) extends WebServiceActor {
  val servicePort = 8000
  val serviceRoute= //<- adjustable depended on client url
    pathPrefix(IntNumber) {
      chatRoomID => {
        chatSuper ! CreateChatRoom(chatRoomID)
        extractClientIP {
          ip =>
            handleWebSocketMessages(newUser(chatRoomID, ip))
        }
      }
    }

  def regNode(port: Int):Unit = {
    val localhost = InetAddress.getLocalHost
    val localIpAddress = localhost.getHostAddress

    // Pass to ChatSupervisor Node-Infor
    context.parent ! RegNodeInfor(localIpAddress, port)

    log.info(s"Server IP Address of System => ${localIpAddress}")
  }

  def incomingMessages(userActor: ActorRef): Sink[Message, NotUsed] = {
    Flow[Message].map {
      // transform websocket message to domain message
      case TextMessage.Strict(text) => UserActor.IncomingMessage(text)

      // PoisonPill asynchronously stops disconnected user actor
      //TODO : to deal with join, leave, text message types
    }.to(Sink.actorRef[UserActor.IncomingMessage](userActor, PoisonPill))
  }

  def outgoingMessages(userActor: ActorRef): Source[Message, NotUsed] = {
    Source.actorRef[UserActor.OutgoingMessage](20000, OverflowStrategy.fail)
      .mapMaterializedValue { outActor =>
        // give the user actor a way to send messages out
        userActor ! UserActor.Connected(outActor)
        NotUsed
      }.map(
      // transform domain message to web socket message
      (outMsg: UserActor.OutgoingMessage) => TextMessage(outMsg.text))
  }

  def newUser(chatRoomID: Int, ip: RemoteAddress): Flow[Message, Message, NotUsed] = {
    // new connection - new user actor
    val userActor = context.actorOf(Props(new UserActor(chatRoomID, chatSuper, ip)))

    // Set Sink & Source
    val incomingMsg = incomingMessages(userActor)
    val outgoingMsg = outgoingMessages(userActor)

    Flow.fromSinkAndSource(incomingMsg, outgoingMsg)
  }

  override val supervisorStrategy = OneForOneStrategy(
    maxNrOfRetries = 10,
    withinTimeRange = 1 minute,
    loggingEnabled = true) {
    case x: Exception =>
      log.info("FIXME: ChatService => " + x.toString)
      Resume
    /*
    case _: ArithmeticException => Restart
    case _: NullPointerException => Resume
    case _: IllegalArgumentException => Stop
    case t =>
      super.supervisorStrategy.decider.applyOrElse( t, (_:Any) => Escalate )
    */
  }

  override def preStart(): Unit = {
    log.info( "Heimdallr Server's staring ..." )
    serviceBind(serviceRoute, servicePort)
    regNode(servicePort)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.info( "Heimdallr Server's restarting ..." )
    //ChatRooms.SystemFailover()
    serviceUnbind()
    preStart()
  }

  override def postRestart(reason: Throwable): Unit = {
    log.info( "Heimdallr Server has restarted." )
  }

  override def postStop(): Unit = {
    //ChatRooms.SystemFailover()
    serviceUnbind()
    log.info( "Heimdallr Server Down !" )
  }

  override def receive: Receive = {
    case HeimdallrError => throw new ArithmeticException()
    case x => log.warning("Unknown message : " + x)
  }
}


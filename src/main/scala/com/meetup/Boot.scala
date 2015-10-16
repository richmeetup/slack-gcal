package com.meetup

import akka.actor.{Props, ActorSystem}
import akka.io.IO
import akka.pattern.ask
import akka.util.Timeout
import com.meetup.slack.SlackCalendarActor
import spray.can.Http
import scala.concurrent.duration._

object Boot extends App {
  implicit val system = ActorSystem("on-spray-can")
  implicit val timeout = Timeout(5.seconds)
  val service = system.actorOf(Props[SlackCalendarActor], "slack-gcal-service")

  IO(Http) ? Http.Bind(service, interface = "localhost", port = 8080)
}

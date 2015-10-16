package com.meetup.slack

import java.io.{File, InputStreamReader}
import java.time._
import java.time.format.DateTimeFormatter
import java.time.temporal.{ChronoUnit, TemporalUnit}

import akka.actor.Actor
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.{GoogleClientSecrets, GoogleAuthorizationCodeFlow}
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.{Charsets, Base64, DateTime => GDateTime}
import com.google.api.client.util.store.{MemoryDataStoreFactory, FileDataStoreFactory}
import com.google.api.services.calendar.{Calendar => GCalendarService, CalendarScopes}
import com.google.api.services.calendar.model.{TimePeriod => GTimePeriod, _}
import spray.http.{Uri, RequestProcessingException, IllegalRequestException, StatusCodes}
import spray.routing._
import spray.http._

import scala.collection.JavaConverters._
import scala.util.{Success, Failure, Try}

class SlackCalendarActor extends Actor with SlackCalendarService {
  override implicit def actorRefFactory = context
  override def receive = runRoute(route)
}

trait SlackCalendarService extends HttpService {
  type ParamState = (String, String, String)
  type TimePeriod = (LocalDateTime, LocalDateTime) // begin, end

  val durationMinutes = 30
  val serverRootUrl = "http://localhost:8080" // "https://<something>.ngrok.io"

  // XXX - this store needs to change
  val credentialsStore = {
    new FileDataStoreFactory(new File("/tmp"))
    //MemoryDataStoreFactory.getDefaultInstance
  }

  val clientSecrets = {
    GoogleClientSecrets.load(
      JacksonFactory.getDefaultInstance(),
      new InputStreamReader(getClass.getResourceAsStream("/slack-gcal-test-browser-client-secrets.json"))
      )
  }

  val authFlow = {
    new GoogleAuthorizationCodeFlow.Builder(
      GoogleNetHttpTransport.newTrustedTransport(),
      JacksonFactory.getDefaultInstance(),
      clientSecrets,
//      Set(CalendarScopes.CALENDAR_READONLY).asJava)
      Set(CalendarScopes.CALENDAR).asJava)
      .setDataStoreFactory(credentialsStore)
      .setAccessType("offline")
      .build()
  }

  val redirectUri = Uri(s"${serverRootUrl}/oauthcallback").toString

  def encodeState(userId: String,
                  command: String,
                  text: String): String = {
    // XXX - probably do something other than : as a the delimiter
    Base64.encodeBase64URLSafeString(s"$userId:$command:$text".getBytes(Charsets.UTF_8))
  }

  def decodeState(state: String): ParamState = {
    new String(Base64.decodeBase64(state), Charsets.UTF_8).split(":").toList match {
      case List(userId, command, text, _*) => (userId, command, text)
      case _ => ("", "", "")
    }
  }

  // XXX - would have to change this at some point to a service account?
  def authorize(userId: String,
                command: String,
                text: String): Either[StandardRoute, Credential] = {
    val state = encodeState(userId, command, text)

    Option(authFlow.loadCredential(userId)).toRight {
      val authorizationUrl = authFlow.newAuthorizationUrl
        .setRedirectUri(redirectUri)
        .setState(state).toString
      complete {
        s"<$authorizationUrl|Click here to authenticate with slack-gcal.>"
      }
    }
  }

  def handleOauthCallback(code: String, state: String): Either[StandardRoute, Credential] = {
    val (userId, _, _) = decodeState(state)

    Try(authFlow.newTokenRequest(code)
      .setRedirectUri(redirectUri)
      .execute()) match {
      case Success(tokenResponse) =>
        Right(authFlow.createAndStoreCredential(tokenResponse, userId))
      case Failure(e) =>
        Left(failWith(new RequestProcessingException(StatusCodes.InternalServerError)))
    }
  }

  def routeCommand(credential: Credential,
                   command: String,
                   userId: String,
                   text: String): StandardRoute = {
    val api = new GCalendarService.Builder(
      GoogleNetHttpTransport.newTrustedTransport,
      JacksonFactory.getDefaultInstance,
      credential
    ).setApplicationName("slack-gcal").build()

    command match {
      case "/whereis" =>
        whereIs(api, userId, text)
      case "/bookroom" =>
        bookRoom(api, userId, text)
      case "/findtimes" =>
        text.split(" ").toList match {
          case (attendeesString :: time :: _) =>
            meetWith(api, userId, attendeesString, time)
          case (attendeesString :: _) =>
            findTimes(api, userId, attendeesString)
        }
      case _ =>
        failWith(new IllegalRequestException(StatusCodes.BadRequest))
    }
  }

  val route =
    path("") {
      post {
        respondWithMediaType(MediaTypes.`text/html`) {
          formFields('user_name, 'command, 'text) {
            (userId, command, text) =>
              authorize(userId, command, text) match {
                case Left(route) => route
                case Right(credential) =>
                  routeCommand(credential, command, userId, text)
              }
          }
        }
      }
    } ~
    path("oauthcallback") {
      get { parameters('code, 'state) {
        (code, state) =>
          handleOauthCallback(code, state) match {
            case Left(route) => route
            case Right(credential) => {
              val (userId, command, text) = decodeState(state)
              complete {
                "All set. Start using /findtimes!"
              }
            }
          }
        }
      }
    }

  /**
   * /whereis <username>
   *
   * Find out where someone is based on their calendar.
   */
  def whereIs(api: GCalendarService, userId: String, text: String): StandardRoute = {
    complete("Hi!")
  }

  def bookRoom(api: GCalendarService, userId: String, text: String): StandardRoute = {
    complete("Hi!")
  }

  private def generateMeetWithLink(userId: String,
                                   attendees: String,
                                   startTime: LocalDateTime): String = {
    val startTimeFormatted = DateTimeFormatter.ISO_INSTANT
      .format(startTime.atZone(ZoneId.systemDefault()))
    val text = s"${attendees} ${startTimeFormatted}"

    Uri(s"${serverRootUrl}/")
      .withQuery(
        "user_name" -> userId,
        "command" -> "meetwith",
        "text" -> text)
      .toString
  }

  /**
   * Finds free time with the <usernames> as attendees.
   */
  def meetWith(api: GCalendarService,
               userId: String,
               attendeesString: String,
               time: String): StandardRoute = {

    val calendarId = s"${userId}@meetup.com"
    val attendees = (List(userId) ++ attendeesString.split(",").toList).map { name =>
      new EventAttendee()
        .setEmail(s"${name.replace("@", "")}@meetup.com")
    }

    val jStartTime = Instant.parse(time)
    val jEndTime = jStartTime.plus(durationMinutes, ChronoUnit.MINUTES)

    val startTime = new EventDateTime()
      .setDateTime(new GDateTime(
        jStartTime.toEpochMilli))

    val endTime = new EventDateTime()
      .setDateTime(new GDateTime(
        jEndTime.toEpochMilli))

    val event = new Event()
      .setSummary(s"Meet w/${attendeesString.split(",").toList.map(_.capitalize).mkString(" & ")}")
      .setLocation(s"Side Room")
      .setDescription(s"This 1-on-1 brought to you by slack-gcal!")
      .setAttendees(attendees.asJava)
      .setStart(startTime)
      .setEnd(endTime)

    Try(api.events()
      .insert(calendarId, event)
      .execute()) match {
      case Success(result) =>
        complete {
          s"<${result.getHtmlLink}|Event created!>"
        }
      case Failure(e) =>
        System.out.println(e)
        failWith(new RequestProcessingException(StatusCodes.InternalServerError))
    }
  }

  /**
   * /findtimes <username>,<username>,...
   *
   * Find free time slots with userId and all usernames present.
   */
  def findTimes(api: GCalendarService,
                userId: String,
                attendeesString: String): StandardRoute = {
    val attendees = (List(userId) ++ attendeesString.split(",").toList).map { name =>
      new FreeBusyRequestItem().setId(s"${name}@meetup.com")
    }

    // try to meet within the next week
    val nowDate = LocalDateTime.now()
    val minDate = nowDate
      .plusHours(1)
      .withMinute(0)
      .withSecond(0)
      .withNano(0)

    val maxDate = nowDate
      .plusDays(8).toLocalDate.atStartOfDay

    val request = new FreeBusyRequest()
      .setItems(attendees.asJava)
      .setTimeMin(new GDateTime(minDate.toInstant(ZoneOffset.UTC).toEpochMilli))
      .setTimeMax(new GDateTime(maxDate.toInstant(ZoneOffset.UTC).toEpochMilli))

    // XXX - also check the dev cal for OOO

    Try(api.freebusy()
      .query(request)
      .execute()) match {
      case Success(result) =>
        System.out.println(result)

        val members: List[String] = result.getCalendars.keySet().asScala.toList
        val busyTimes: List[TimePeriod] =
          result.getCalendars.values().asScala.toList.flatMap { busyTime =>
            busyTime.getBusy.asScala
          }.map { timePeriod =>
            (LocalDateTime.ofInstant(Instant.ofEpochMilli(timePeriod.getStart.getValue),
              ZoneId.systemDefault()),
             LocalDateTime.ofInstant(Instant.ofEpochMilli(timePeriod.getEnd.getValue),
              ZoneId.systemDefault()))
          }

        // calculate free times
        val freeTimes: List[TimePeriod] =
          calculateFreeTimes(minDate, maxDate, durationMinutes, busyTimes)

        val dateFormat = DateTimeFormatter.ofPattern("EEEE, MMMM d, h:mm a")
        val isoFormat = DateTimeFormatter.ISO_INSTANT

        complete {
          s"""Found *${freeTimes.length} free slots* for a ${durationMinutes}-minute meeting. Here are the Top 10:
          ```${freeTimes.take(10).map(freeTime =>
            s"(${isoFormat.format(freeTime._1.atZone(ZoneId.systemDefault()).toInstant)})" +
            s" ${dateFormat.format(freeTime._1)}").mkString("\n")}```"""
        }
      case Failure(e) =>
        System.out.println(e)
        failWith(new RequestProcessingException(StatusCodes.InternalServerError))
    }
  }

  private def timeOverlaps(minDate: LocalDateTime,
                           maxDate: LocalDateTime,
                           busyTimes: List[TimePeriod]): Boolean = {
    for (busyTime <- busyTimes) {
      if ((busyTime._1.isBefore(minDate) || busyTime._1.isEqual(minDate)) && busyTime._2.isAfter(minDate) ||
          busyTime._1.isBefore(maxDate) && busyTime._2.isAfter(maxDate)) {
        return true // XXX - this doesn't seem kosher
      }
    }
    false
  }

  private def calculateFreeTimes(minDate: LocalDateTime,
                                 maxDate: LocalDateTime,
                                 durationMinutes: Long,
                                 busyTimes: List[TimePeriod]): List[TimePeriod] = {
    var freeTimes: List[TimePeriod] = List()

    // create a list of all 30 min blocks in the next 7 days
    var currMinDate = minDate
    var currMaxDate = minDate.plusMinutes(durationMinutes)
    while (currMinDate.isBefore(maxDate)) {
      // no meetings before 11am or after 6pm, or during lunch 1-2pm, or weekends
      if (!((currMinDate.getHour < 11 || currMinDate.getHour >= 18 || (currMaxDate.getHour >= 18 &&
              !(currMaxDate.getHour == 18 && currMaxDate.getMinute == 0))) ||
            (currMinDate.getHour <= 13 && (currMaxDate.getHour >= 13 &&
              !(currMaxDate.getHour == 13 && currMaxDate.getMinute == 0))) ||
            (currMinDate.getDayOfWeek.equals(DayOfWeek.SATURDAY) ||
             currMinDate.getDayOfWeek.equals(DayOfWeek.SUNDAY))) &&
          !timeOverlaps(currMinDate, currMaxDate, busyTimes)) {
        freeTimes = freeTimes :+ (currMinDate, currMaxDate)
      }
      //currMinDate = currMinDate.plusMinutes(15) // check in increments of 15 min
      currMinDate = currMinDate.plusMinutes(30)
      currMaxDate = currMinDate.plusMinutes(durationMinutes)
    }

    freeTimes
  }
}

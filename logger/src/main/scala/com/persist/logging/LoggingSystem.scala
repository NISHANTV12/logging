package com.persist.logging

import akka.actor.{Props, ActorSystem}
import LogActor._
import TimeActorMessages.TimeDone
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import scala.concurrent.{Promise, ExecutionContext, Future}
import scala.language.postfixOps
import com.persist.JsonOps._

/**
 *
 * @param system the actor system.
 * @param serviceName name of the service (to log).
 * @param serviceVersion version of the service (to log).
 * @param host host name (to log).
 * @param appenderBuilders optional sequence of log appenders to use.
 *                         Default is to use built-in stdout and file appenders.
 */
case class LoggingSystem(private val system: ActorSystem,
                         private val serviceName: String,
                         private val serviceVersion: String,
                         private val host: String,
                         private val appenderBuilders: Seq[LogAppenderBuilder] = Seq(StdOutAppender, FileAppender)
                          ) extends ClassLogging {

  import LoggingLevels._

  LoggingState.loggingSys = this

  private[this] val loggingConfig = system.settings.config.getConfig("com.persist.logging")
  private[this] val levels = loggingConfig.getString("logLevel")
  private[this] val defaultLevel: Level = if (Level.hasLevel(levels)) {
    Level(levels)
  } else {
    throw new Exception("Bad value for com.persist.logging.logLevel")
  }
  @volatile var logLevel = defaultLevel
  private[this] val akkaLogLevelS = loggingConfig.getString("akkaLogLevel")
  private[this] val defaultAkkaLogLevel: Level = if (Level.hasLevel(akkaLogLevelS)) {
    Level(akkaLogLevelS)
  } else {
    throw new Exception("Bad value for com.persist.logging.akkaLogLevel")
  }
  @volatile private[this] var akkaLogLevel = defaultAkkaLogLevel
  private[this] var slf4jLogLevelS = loggingConfig.getString("slf4jLogLevel")
  private[this] val defaultSlf4jLogLevel: Level = if (Level.hasLevel(slf4jLogLevelS)) {
    Level(slf4jLogLevelS)
  } else {
    throw new Exception("Bad value for com.persist.logging.slf4jLogLevel")
  }
  @volatile private[this] var slf4jLogLevel = defaultSlf4jLogLevel
  private[this] val gc = loggingConfig.getBoolean("gc")
  private[this] val time = loggingConfig.getBoolean("time")

  private[this] implicit val ec: ExecutionContext = system.dispatcher
  private[this] val done = Promise[Unit]
  private[this] val timeActorDonePromise = Promise[Unit]

  /**
   * Standard headers.
   */
  val standardHeaders = Map[String,RichMsg]("@version" -> 1, "@host" -> host,
    "@service" -> Map[String,RichMsg]("name"->serviceName,"version"->serviceVersion))

  setLevel(defaultLevel)
  LoggingState.loggerStopping = false
  LoggingState.doTime = false
  LoggingState.timeActorOption = None

  private[this] val appenders = appenderBuilders.map {
    _.apply(system, standardHeaders)
  }

  private[this] val logActor = system.actorOf(LogActor.props(done, standardHeaders,
    appenders, defaultLevel, defaultSlf4jLogLevel, defaultAkkaLogLevel), name = "LoggingActor")
  LoggingState.logger = Some(logActor)

  private[logging] val gcLogger: Option[GcLogger] = if (gc) {
    Some(GcLogger())
  } else {
    None
  }

  if (time) {
    // Start timing actor
    LoggingState.doTime = true
    val timeActor = system.actorOf(Props(classOf[TimeActor], timeActorDonePromise), name = "TimingActor")
    LoggingState.timeActorOption = Some(timeActor)
  } else {
    timeActorDonePromise.success(())
  }

  // Deal with messages send before logger was ready
  LoggingState.msgs.synchronized {
    if (LoggingState.msgs.size > 0) {
      log.info(s"Saw ${LoggingState.msgs.size} messages before logger start")(() => DefaultSourceLocation)
      for (msg <- LoggingState.msgs) {
        LoggingState.sendMsg(msg)
      }
    }
    LoggingState.msgs.clear()
  }

  /**
   * Get logging levels.
   * @return the current and default logging levels.
   */
  def getLevel: Levels = Levels(logLevel, defaultLevel)

  /**
   * Changes the logger API logging level.
   * @param level the new logging level for the logger API.
   */
  def setLevel(level: Level): Unit = {
    import LoggingState._

    logLevel = level
    val doTrace1 = level.pos <= TRACE.pos
    if (doTrace != doTrace1) doTrace = doTrace1
    val doDebug1 = level.pos <= DEBUG.pos
    if (doDebug != doDebug1) doDebug = doDebug1
    val doInfo1 = level.pos <= INFO.pos
    if (doInfo != doInfo1) doInfo = doInfo1
    val doWarn1 = level.pos <= WARN.pos
    if (doWarn != doWarn1) doWarn = doWarn1
    val doError1 = level.pos <= ERROR.pos
    if (doError != doError1) doError = doError1
  }

  /**
   * Get Akka logging levels
   * @return the current and default Akka logging levels.
   */
  def getAkkaLevel: Levels = Levels(akkaLogLevel, defaultAkkaLogLevel)

  /**
   * Changes the Akka logger logging level.
   * @param level the new logging level for the Akka logger.
   */
  def setAkkaLevel(level: Level): Unit = {
    logActor ! SetAkkaLevel(level)
  }

  /**
   * Get the Slf4j logging levels.
   * @return the current and default Slf4j logging levels.
   */
  def getSlf4jLevel: Levels = Levels(slf4jLogLevel, defaultSlf4jLogLevel)

  /**
   * Changes the slf4j logging level.
   * @param level the new logging level for slf4j.
   */
  def setSlf4jLevel(level: Level): Unit = {
    logActor ! SetSlf4jLevel(level)
  }

  /**
   * Sets or removes the logging filter.
   * Filter applies only to the common log.
   * You may want to increase the logging level after adding the filter.
   * Note that a filter together with an increased logging level will
   * require more processing overhead.
   * @param filter  takes the complete common log message and the logging level
   *                and returns false if
   *                that message is to be discarded.
   */
  def setFilter(filter: Option[(Map[String, RichMsg], Level) => Boolean]): Unit = {
    logActor ! SetFilter(filter)
  }

  /**
   * Shut down the logging system.
   * @return  future completes when the logging system is shut down.
   */
  def stop: Future[Unit] = {
    def stopAkka(): Future[Unit] = {
      LoggingState.sendMsg(LastAkkaMessage)
      LoggingState.akkaStopPromise.future
    }

    def stopTimeActor(): Future[Unit] = {
      LoggingState.timeActorOption foreach {
        case timeActor => timeActor ! TimeDone
      }
      timeActorDonePromise.future
    }

    def stopLogger(): Future[Unit] = {
      LoggingState.loggerStopping = true
      logActor ! StopLogging
      done.future
    }

    def finishAppenders(): Future[Unit] = {
      Future.sequence(appenders map (_.finish())).map(x => ())
    }

    def stopAppenders(): Future[Unit] = {
      Future.sequence(appenders map (_.stop())).map(x => ())
    }

    //Stop gc logger
    gcLogger foreach (_.stop)

    // Stop Slf4j
    val loggerContext = LoggerFactory.getILoggerFactory().asInstanceOf[LoggerContext]
    loggerContext.stop()

    for {
      akkaTimeDone <- stopAkka() zip stopTimeActor()
      logActorDone <- finishAppenders()
      logActorDone <- stopLogger()
      appendersDone <- stopAppenders()
    } yield appendersDone
  }
}


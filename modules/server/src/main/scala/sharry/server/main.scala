package sharry.server

import java.time.Instant
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.nio.file.{Path, Paths}
import java.nio.channels.AsynchronousChannelGroup
import scala.concurrent.duration._

import fs2._
import scodec.{Attempt, Codec}
import spinoco.fs2.http
import spinoco.fs2.http.HttpResponse
import spinoco.fs2.http.body.BodyEncoder
import spinoco.fs2.http.routing._
import spinoco.protocol.http.HttpRequestHeader
import spinoco.protocol.http.HttpStatusCode
import spinoco.protocol.http.codec.HttpRequestHeaderCodec

import org.log4s._

import sharry.common.BuildInfo
import sharry.common.file._
import sharry.common.streams
import sharry.common.version
import sharry.store.evolution
import sharry.server.codec.HttpHeaderCodec

object main {
  implicit val logger = getLogger
  val ES = Executors.newCachedThreadPool(Strategy.daemonThreadFactory("sharry-server-ACG"))
  implicit val ACG = AsynchronousChannelGroup.withThreadPool(ES) // http.server requires a group
  implicit val S = Strategy.fromExecutor(ES) // Async (Task) requires a strategy
  implicit val SCH = Scheduler.fromFixedDaemonPool(5, "sharry-cleanup")

  def main(args: Array[String]): Unit = {
    logger.info(s"""
       |––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––
       | Sharry ${version.longVersion} build at ${BuildInfo.builtAtString.dropRight(4)}UTC is starting up …
       |––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––""".stripMargin)
    val startupCfg = StartConfig.parse(args)
    startupCfg.setup.unsafeRun
    val app = new App(config.Config.default)

    logger.info("""
       |––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––
       | • Running initialize tasks …
       |––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––""".stripMargin)
    evolution(app.cfg.jdbc.url).runChanges(app.jdbc).unsafeRun
    Task.start(startCleanup(app)).unsafeRun

    val shutdown =
      streams.slog[Task](_.info("Closing database")) ++
      Stream.eval(Task.delay(app.jdbc.kernel.close()))

    val server = http.server[Task](
      bindTo = new InetSocketAddress(app.cfg.webConfig.bindHost, app.cfg.webConfig.bindPort),
      requestCodec = requestHeaderCodec,
      requestHeaderReceiveTimeout = 10.seconds,
      sendFailure = handleSendFailure _, // (Option[HttpRequestHeader], HttpResponse[F], Throwable) => Stream[F, Nothing],
      requestFailure = logRequestErrors _)(route(app.endpoints)).
      onFinalize(shutdown.run)

    logger.info(s"""
       |––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––
       | • Starting http server at ${app.cfg.webConfig.bindHost}:${app.cfg.webConfig.bindPort}
       |––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––––""".stripMargin)

    if (startupCfg.console) {
      startWithConsole(server)
    } else {
      server.run.unsafeRun
    }
  }

  private def startWithConsole(server: Stream[Task,Unit]): Unit = {
    // thanks to https://gitter.im/fs2-http/Lobby?at=591045fd631b8e4e61b39a26
    val interrupt = async.signalOf[Task, Boolean](false).unsafeRun
    val wait = Task.start(server.interruptWhen(interrupt).run).unsafeRun
    println("Hit RETURN to stop the server")
    scala.io.StdIn.readLine()
    interrupt.set(true).unsafeRun
    wait.unsafeRun
    logger.info("Sharry has stopped")
  }

  private def startCleanup(app: App)(implicit SCH: Scheduler): Task[Unit] = {
    val cfg = app.uploadConfig
    if (cfg.cleanupEnable) {
      logger.info(s"Scheduling cleanup job every ${cfg.cleanupInterval}")
      val stream = time.awakeEvery[Task](cfg.cleanupInterval.asScala).
        flatMap({ _ =>
          logger.info("Running cleanup job")
          val since = Instant.now.minus(cfg.cleanupInvalidAge.asJava)
          app.uploadStore.cleanup(since).
            through(streams.ifEmpty(Stream.emit(0))).sum.
            evalMap(n => Task.delay(logger.info(s"Cleanup job removed $n uploads"))) ++
            Stream.eval(Task.delay(logger.info("Cleanup job done."))).drain
        })

      stream.run
    } else {
      logger.info("Not starting cleanup job as requested")
      Task.now(())
    }
  }

  private def logRequestErrors[F[_]](error: Throwable): Stream[F, HttpResponse[F]] = Stream.suspend {
    implicit val enc = BodyEncoder.utf8String
    logger.error(error)("Error in request")
    Stream.emit(HttpResponse(HttpStatusCode.InternalServerError).withBody(error.getClass + ":" + error.getMessage))
  }

  private def handleSendFailure[F[_]](header: Option[HttpRequestHeader], response: HttpResponse[F], err:Throwable): Stream[F, Nothing] = {
    Stream.suspend {
      err match {
        case _: java.io.IOException if err.getMessage == "Broken pipe" || err.getMessage == "Connection reset by peer" =>
          logger.warn(s"Error sending response: ${err.getMessage}! Request headers: ${header}")
        case _ =>
          logger.error(err)(s"Error sending response! Request headers: ${header}")
      }
      Stream.empty
    }
  }

  private def requestHeaderCodec: Codec[HttpRequestHeader] = {
    val codec = HttpRequestHeaderCodec.codec(HttpHeaderCodec.codec(Int.MaxValue))
    Codec (
      h => codec.encode(h),
      v => codec.decode(v) match {
        case a: Attempt.Successful[_] => a
        case f@ Attempt.Failure(cause) =>
          logger.error(s"Error parsing request ${v.decodeUtf8} \n$cause")
          f
      }
    )
  }

  case class StartConfig(console: Boolean, configFile: Option[Path]) {
    def setup: Task[Unit] = Task.delay {
      configFile.foreach { f =>
        logger.info(s"Using config file $f")
        System.setProperty("config.file", f.toString)
      }
    }
  }

  object StartConfig {

    def parse(args: Seq[String]): StartConfig = {
      val console = {
        args.exists(_ == "--console") ||
        Option(System.getProperty("sharry.console")).
          exists(_ equalsIgnoreCase "true")
      }

      val file = args.find(_ != "--console").
        map(f => Paths.get(f)).
        orElse {
          Option(System.getProperty("sharry.optionalConfig")).
            map(f => Paths.get(f)).
            filter(_.exists)
        }

      StartConfig(console, file)
    }
  }
}

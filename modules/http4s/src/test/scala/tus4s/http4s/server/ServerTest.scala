package tus4s.http4s.server

import cats.effect.*

import com.comcast.ip4s.*
import org.http4s.DecodeFailure
import org.http4s.HttpVersion
import org.http4s.Response
import org.http4s.Status
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.middleware.Logger
import tus4s.core.TusProtocol

abstract class ServerTest extends IOApp:
  scribe.Logger.root.withMinimumLevel(scribe.Level.Debug).replace()

  def tusBackend: IO[TusProtocol[IO]]

  def port: Port = port"8888"

  def tusEndpoint(backend: TusProtocol[IO]) =
    TusEndpointBuilder[IO](backend)
      .withBaseUri(uri"/files")
      .withRetrieve(Retrieve.rangeGet[IO])
      .build
      .routes

  def createApp(backend: TusProtocol[IO]) =
    ErrorHandling.Recover.messageFailure(
      Logger.httpApp(true, false)(
        Router(
          "/" -> IndexRoutes.routes,
          "files" -> tusEndpoint(backend)
        ).orNotFound
      )
    )

  def run(args: List[String]): IO[ExitCode] =
    for
      backend <- tusBackend
      app = Router(
        "/" -> IndexRoutes.routes,
        "files" -> tusEndpoint(backend)
      ).orNotFound
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port)
        .withHttpApp(app)
        .withErrorHandler {
          case ex: DecodeFailure =>
            IO.println(s"Error decoding message! $ex")
              .as(ex.toHttpResponse(HttpVersion.`HTTP/1.1`))
          case ex =>
            IO.println(s"Service raised an error! $ex")
              .as(Response(status = Status.InternalServerError))
        }
        .build
        .useForever
    yield ExitCode.Success

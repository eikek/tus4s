package tus4s.http4s.server

import cats.effect.*
import fs2.io.file.Path

import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.middleware.Logger
import tus4s.core.TusProtocol
import tus4s.core.data.ByteSize
import tus4s.fs.FsTusProtocol
import tus4s.pg.ConnectionResource
import tus4s.pg.PgConfig
import tus4s.pg.PgTusProtocol
import org.http4s.Response
import org.http4s.Status
import org.http4s.DecodeFailure
import org.http4s.HttpVersion

object ServerTest extends IOApp:
  val pgBackend = PgTusProtocol.create[IO](
    PgConfig(
      ConnectionResource.simple("jdbc:postgresql://localhost:5432/tus_test"),
      "tus_files"
    )
  )
  val fsBackend = FsTusProtocol.create[IO](Path("/tmp/tus-test"), Some(ByteSize.mb(500)))
  val tusBackend = fsBackend

  def tusEndpoint(backend: TusProtocol[IO]) =
    TusEndpointBuilder[IO](backend)
      .withBaseUri(uri"/files")
      .withRetrieve(Retrieve.simpleGet[IO])
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

  scribe.Logger.root.withMinimumLevel(scribe.Level.Debug).replace()

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
        .withPort(port"8888")
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

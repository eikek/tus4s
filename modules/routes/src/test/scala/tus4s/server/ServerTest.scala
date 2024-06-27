package tus4s.server

import cats.effect.*
import fs2.io.file.Path

import com.comcast.ip4s.*
import tus4s.data.ByteSize
import tus4s.fs.FsTusProtocol
import tus4s.protocol.TusProtocol
import org.http4s.HttpRoutes
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.middleware.Logger

object ServerTest extends IOApp:
  val tusBackend = FsTusProtocol.create[IO](Path("/tmp/tus-test"), Some(ByteSize.mb(500)))

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
        .build
        .useForever
    yield ExitCode.Success

package http4stus.server

import cats.effect.*
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import http4stus.fs.FsTusProtocol
import fs2.io.file.Path
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.middleware.{ErrorAction, Logger}
import http4stus.protocol.TusProtocol

object ServerTest extends IOApp:

  val tusBackend = FsTusProtocol.create[IO](Path("/tmp/tus-test"), None)
  def createApp(backend: TusProtocol[IO]) =
    ErrorAction.httpApp[IO](
      Logger.httpApp(true, false)(
        Router(
          "files" -> TusEndpointBuilder[IO](backend)
            .withBaseUri(uri"/files")
            .build
            .routes
        ).orNotFound
      ),
      (req, ex) => scribe.cats.io.error(s"Request $req failed", ex)
    )

  scribe.Logger.root.withMinimumLevel(scribe.Level.Debug).replace()

  def run(args: List[String]): IO[ExitCode] =
    for
      backend <- tusBackend
      app = createApp(backend)
      _ <- EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port"8888")
        .withHttpApp(app)
        .build
        .useForever
    yield ExitCode.Success

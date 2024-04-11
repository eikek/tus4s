package http4stus.server

import cats.effect.*
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import http4stus.fs.FsTusProtocol
import fs2.io.file.Path
import org.http4s.implicits.*
import org.http4s.server.Router
import org.http4s.server.middleware.Logger

object ServerTest extends IOApp:

  val tusBackend = FsTusProtocol[IO](Path("/tmp/tus-test"), None)
  val app =
    Logger.httpApp(true, false)(
      Router(
        "files" -> TusEndpointBuilder[IO](tusBackend)
          .withBaseUri(uri"/files")
          .build
          .routes
      ).orNotFound
    )

  scribe.Logger.root.withMinimumLevel(scribe.Level.Debug).replace()

  def run(args: List[String]): IO[ExitCode] =
    EmberServerBuilder
      .default[IO]
      .withHost(host"0.0.0.0")
      .withPort(port"8888")
      .withHttpApp(app)
      .build
      .useForever
      .as(ExitCode.Success)

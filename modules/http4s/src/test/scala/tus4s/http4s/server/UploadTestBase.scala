package tus4s.http4s.server

import scala.concurrent.duration.*
import munit.CatsEffectSuite
import cats.effect.*
import org.http4s.implicits.*
import tus4s.http4s.Endpoint
import tus4s.core.TusConfig
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.Router
import org.http4s.ember.server.EmberServerBuilder
import com.comcast.ip4s.*
import java.net.ServerSocket
import io.tus.java.client.*
import java.net.URL
import java.io.File
import java.util.concurrent.atomic.AtomicReference

abstract class UploadTestBase(endpoint: Resource[IO, Endpoint[IO]])
    extends CatsEffectSuite:
  scribe.Logger.root.withMinimumLevel(scribe.Level.Debug).replace()
  val config: TusConfig = endpoint.use(e => IO.pure(e.config)).unsafeRunSync()

  def createApp(endpoint: Endpoint[IO]) =
    ErrorHandling.Recover.messageFailure(
      Router("files" -> endpoint.routes).orNotFound
    )

  val findFreePort: IO[Port] = IO.blocking {
    val socket = new ServerSocket(0)
    try {
      socket.setReuseAddress(true)
      Port.fromInt(socket.getLocalPort).get
    } finally socket.close()
  }

  def server: Resource[IO, String] =
    for
      ep <- endpoint
      port <- Resource.eval(findFreePort)
      app = createApp(ep)

      s <- EmberServerBuilder
        .default[IO]
        .withHost(host"0.0.0.0")
        .withPort(port)
        .withHttpApp(app)
        .build
        .onFinalize(IO.println("Server stopped"))

      _ <- Resource.sleep[IO](1.seconds)
    yield s"http://0.0.0.0:$port/files"

  def clientFor(addr: String) =
    println(s"++++ $addr")
    val client = new TusClient()
    client.setUploadCreationURL(new URL(addr))
    client.enableResuming(new TusURLMemoryStore())
    client

  extension (self: TusClient)
    def upload(file: File, chunkSize: Int = 8192) =
      val tu = new TusUpload(file)
      val url = new AtomicReference[String]()
      val exec =
        new TusExecutor {
          override protected def makeAttempt(): Unit =
            val uploader = self.resumeOrCreateUpload(tu)
            uploader.setChunkSize(chunkSize)
            while (uploader.uploadChunk() != -1) {}
            uploader.finish()
            url.set(uploader.getUploadURL.toString)
        }

      IO(exec.makeAttempts()).flatMap(_ => IO(url.get))

  test("simple file upload"):
    server.use { url =>
      for
        client <- IO(clientFor(url))
        file = File("renovate.json")
        _ <- IO.println(s"Doing things: ${file.getAbsolutePath()}")
        r <- client.upload(file)
        _ <- IO.println(r)
      yield ()
    }

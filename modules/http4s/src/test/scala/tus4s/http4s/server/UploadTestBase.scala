package tus4s.http4s.server

import munit.CatsEffectSuite
import cats.effect.*
import org.http4s.implicits.*
import tus4s.http4s.Endpoint
import tus4s.core.TusConfig
import org.http4s.server.middleware.ErrorHandling
import org.http4s.server.Router
import org.http4s.ember.server.EmberServerBuilder
import com.comcast.ip4s.*
import org.http4s.server.Server
import java.net.ServerSocket
import io.tus.java.client.*
import java.net.URL
import java.io.File

abstract class UploadTestBase(endpoint: Resource[IO, Endpoint[IO]])
    extends CatsEffectSuite:

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

  def server: Resource[IO, Server] =
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
    yield s

  def clientFor(s: Server) =
    val client = new TusClient()
    val addr = s"http://${s.addressIp4s}/files"
    client.setUploadCreationURL(new URL(addr))
    client.enableResuming(new TusURLMemoryStore())
    client

  def executor(upload: File, client: TusClient, chunkSize: Int = 8 * 1024) =
    new TusExecutor {
      override protected def makeAttempt(): Unit = {
        val tu = new TusUpload(upload)
        val uploader = client.resumeOrCreateUpload(tu)
        uploader.setChunkSize(chunkSize)
        while (uploader.uploadChunk() != -1) {}
        uploader.finish()
      }
    }

  test("simple file upload"):
    ???

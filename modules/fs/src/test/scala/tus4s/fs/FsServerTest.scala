package tus4s.fs

import cats.effect.IO
import fs2.io.file.Path

import tus4s.core.data.ByteSize
import tus4s.http4s.server.ServerTest

object FsServerTest extends ServerTest:

  val tusBackend = FsTusProtocol.create[IO](Path("/tmp/tus-test"), Some(ByteSize.mb(500)))

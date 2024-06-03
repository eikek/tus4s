package http4stus.server

import cats.effect.*
import fs2.io.file.{Files, Path}

import http4stus.data.ByteSize
import http4stus.fs.FsTusProtocol

class TusEndpointSpec
    extends TusEndpointSuite(
      TusEndpointSpec.createFsProtocol.map(p => TusEndpointBuilder(p).build)
    )

object TusEndpointSpec:

  val createFsProtocol: Resource[IO, FsTusProtocol[IO]] =
    val base: Option[Path] = None
    Files.forIO
      .tempDirectory(base, "tus-fs", None)
      .map(dir => new FsTusProtocol[IO](dir, Some(ByteSize.bytes(50))))

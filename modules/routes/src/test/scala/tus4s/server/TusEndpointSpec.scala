package tus4s.server

import cats.effect.*
import fs2.io.file.{Files, Path}

import tus4s.data.ByteSize
import tus4s.fs.FsTusProtocol

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

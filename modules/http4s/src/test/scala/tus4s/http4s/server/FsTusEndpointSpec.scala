package tus4s.http4s.server

import cats.effect.*
import fs2.io.file.{Files, Path}

import tus4s.core.data.ByteSize
import tus4s.fs.FsTusProtocol

class FsTusEndpointSpec
    extends TusEndpointSuite(
      FsTusEndpointSpec.createFsProtocol.map(p =>
        TusEndpointBuilder(p).withRetrieve(Retrieve.simpleGet[IO]).build
      )
    )

object FsTusEndpointSpec:

  val createFsProtocol: Resource[IO, FsTusProtocol[IO]] =
    val base: Option[Path] = None
    Files.forIO
      .tempDirectory(base, "tus-fs", None)
      .map(dir => new FsTusProtocol[IO](dir, Some(ByteSize.bytes(50))))

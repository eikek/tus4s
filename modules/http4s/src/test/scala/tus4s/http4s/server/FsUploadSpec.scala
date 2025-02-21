package tus4s.http4s.server

import cats.effect.*
import fs2.io.file.{Files, Path}
import org.http4s.implicits.*

import tus4s.core.data.ByteSize
import tus4s.fs.FsTusProtocol

class FsUploadSpec
    extends UploadTestBase(
      FsUploadSpec.createFsProtocol.map(p =>
        TusEndpointBuilder(p)
          .withRetrieve(Retrieve.simpleGet[IO])
          .withBaseUri(uri"/files")
          .build
      )
    )

object FsUploadSpec:

  val createFsProtocol: Resource[IO, FsTusProtocol[IO]] =
    val base: Option[Path] = None
    Files.forIO
      .tempDirectory(base, "tus-upload-fs", None)
      .map(dir => new FsTusProtocol[IO](dir, Some(ByteSize.mb(5))))

package tus4s.fs

import cats.effect.*
import fs2.io.file.Files

import tus4s.core.TusProtocolTestBase
import tus4s.core.data.*

class FsTusProtocolTest extends TusProtocolTestBase:

  def tusProtocol(maxSize: Option[ByteSize]) = for {
    path <- Files[IO].tempDirectory
    p <- Resource.eval(
      FsTusProtocol.create[IO](path, maxSize, true, true, true)
    )
  } yield p

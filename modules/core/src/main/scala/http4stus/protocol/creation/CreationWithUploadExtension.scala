package http4stus.protocol.creation

import fs2.Stream
import http4stus.data.*

trait CreationWithUploadExtension[F[_]]:
  def create(meta: MetadataMap, length: Option[ByteSize], data: Stream[F, Byte]): F[UploadId]

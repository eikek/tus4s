package http4stus.protocol.creation

import http4stus.data.*

trait CreationExtension[F[_]]:
  def create(meta: MetadataMap, length: Option[ByteSize]): F[UploadId]

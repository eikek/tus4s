package http4stus.data

import fs2.Stream

final case class UploadChunk[F[_]](
    id: UploadId,
    offset: ByteSize,
    contentLength: Option[ByteSize],
    uploadLength: Option[ByteSize],
    data: Stream[F, Byte]
)

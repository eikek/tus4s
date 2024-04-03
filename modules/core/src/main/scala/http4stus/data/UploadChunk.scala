package http4stus.data

import fs2.Stream
import http4stus.protocol.headers.UploadChecksum

final case class UploadChunk[F[_]](
    id: UploadId,
    offset: ByteSize,
    contentLength: Option[ByteSize],
    uploadLength: Option[ByteSize],
    checksum: Option[UploadChecksum],
    isPartial: Boolean,
    data: Stream[F, Byte]
)

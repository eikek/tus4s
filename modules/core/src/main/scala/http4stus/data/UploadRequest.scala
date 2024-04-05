package http4stus.data

import fs2.Stream

import http4stus.protocol.headers.UploadChecksum

final case class UploadRequest[F[_]](
    offset: ByteSize,
    contentLength: Option[ByteSize],
    uploadLength: Option[ByteSize],
    checksum: Option[UploadChecksum],
    isPartial: Boolean,
    meta: MetadataMap,
    hasContent: Boolean,
    data: Stream[F, Byte]
)

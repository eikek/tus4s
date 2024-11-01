package tus4s.core.data

import fs2.Stream

import scodec.bits.ByteVector

final case class UploadRequest[F[_]](
    offset: ByteSize,
    contentLength: Option[ByteSize],
    uploadLength: Option[ByteSize],
    checksum: Option[UploadRequest.Checksum],
    isPartial: Boolean,
    meta: MetadataMap,
    hasContent: Boolean,
    data: Stream[F, Byte]
)

object UploadRequest:

  final case class Checksum(algorithm: ChecksumAlgorithm, checksum: ByteVector)

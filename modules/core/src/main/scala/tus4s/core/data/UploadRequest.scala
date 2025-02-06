package tus4s.core.data

import fs2.Stream

import scodec.bits.ByteVector
import cats.data.NonEmptyList
import fs2.Chunk

final case class UploadRequest[F[_]](
    offset: ByteSize,
    contentLength: Option[ByteSize],
    uploadLength: Option[ByteSize],
    checksum: Option[UploadRequest.Checksum],
    isPartial: Boolean,
    meta: MetadataMap,
    hasContent: Boolean,
    data: Stream[F, Byte]
):

  def withMeta(key: MetadataMap.Key, value: ByteVector): UploadRequest[F] =
    copy(meta = meta.withValue(key, value))

  def withMeta(keys: NonEmptyList[MetadataMap.Key], value: ByteVector): UploadRequest[F] =
    copy(meta = keys.foldLeft(meta)(_.withValue(_, value)))

  def withPartial(flag: Boolean): UploadRequest[F] =
    copy(isPartial = flag)

object UploadRequest:

  final case class Checksum(algorithm: ChecksumAlgorithm, checksum: ByteVector)

  def fromByteVector[F[_]](bv: ByteVector): UploadRequest[F] =
    UploadRequest[F](
      offset = ByteSize.zero,
      contentLength = Some(ByteSize.bytes(bv.length)),
      uploadLength = Some(ByteSize.bytes(bv.length)),
      checksum = None,
      isPartial = false,
      meta = MetadataMap.empty,
      hasContent = true,
      data = Stream.chunk(Chunk.byteVector(bv))
    )

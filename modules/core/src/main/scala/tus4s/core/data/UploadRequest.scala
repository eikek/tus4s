package tus4s.core.data

import cats.data.NonEmptyList
import fs2.Chunk
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
):

  def withMeta(key: MetadataMap.Key, value: ByteVector): UploadRequest[F] =
    copy(meta = meta.withValue(key, value))

  def withMeta(keys: NonEmptyList[MetadataMap.Key], value: ByteVector): UploadRequest[F] =
    copy(meta = keys.foldLeft(meta)(_.withValue(_, value)))

  def withPartial(flag: Boolean): UploadRequest[F] =
    copy(isPartial = flag)

object UploadRequest:

  final case class Checksum(algorithm: ChecksumAlgorithm, checksum: ByteVector):
    def asString: String = s"${algorithm.name}:${checksum.toHex}"

  object Checksum:
    def fromString(s: String): Either[String, Checksum] =
      s.split(':').toList match
        case alg :: sum :: Nil =>
          for
            ca <- ChecksumAlgorithm.fromString(alg)
            cc <- ByteVector.fromHexDescriptive(sum)
          yield Checksum(ca, cc)
        case _ =>
          Left(s"Invalid checksum: $s")

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

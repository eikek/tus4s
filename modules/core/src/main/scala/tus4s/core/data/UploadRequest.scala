package tus4s.core.data

import cats.data.NonEmptyList
import cats.syntax.all.*
import fs2.Chunk
import fs2.Stream
import fs2.hashing.Hash

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

  def dataLimit(maxSize: Option[ByteSize]): Stream[F, Byte] =
    maxSize match
      case Some(ms) => data.take(ms.toBytes + 1)
      case None     => data

  def dataChunked(chunkSize: ByteSize, maxSize: Option[ByteSize]) =
    dataLimit(maxSize).chunkN(chunkSize.toBytes.toInt, allowFewer = true)

  def withMeta(key: MetadataMap.Key, value: ByteVector): UploadRequest[F] =
    copy(meta = meta.withValue(key, value))

  def withMeta(keys: NonEmptyList[MetadataMap.Key], value: ByteVector): UploadRequest[F] =
    copy(meta = keys.foldLeft(meta)(_.withValue(_, value)))

  def withPartial(flag: Boolean): UploadRequest[F] =
    copy(isPartial = flag)

  def toStateNoContent(id: UploadId) =
    UploadState(
      id,
      ByteSize.zero,
      uploadLength,
      meta,
      Option.when(isPartial)(ConcatType.Partial)
    )

object UploadRequest:
  final case class Checksum(algorithm: ChecksumAlgorithm, checksum: ByteVector):
    def asString: String = s"${algorithm.name}:${checksum.toHex}"
    def hash: Hash = Hash(Chunk.byteVector(checksum))

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
      uploadLength = Some(ByteSize.bytes(bv.length)).filter(_ > ByteSize.zero),
      checksum = None,
      isPartial = false,
      meta = MetadataMap.empty,
      hasContent = bv.nonEmpty,
      data = Stream.chunk(Chunk.byteVector(bv))
    )

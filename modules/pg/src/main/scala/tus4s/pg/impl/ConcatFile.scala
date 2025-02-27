package tus4s.pg.impl

import cats.data.NonEmptyVector
import cats.syntax.all.*

import tus4s.core.data.ByteRange
import tus4s.core.data.ByteSize
import tus4s.core.data.UploadState

final case class ConcatFile(state: UploadState, parts: NonEmptyVector[(Long, ByteSize)]):

  def oids: NonEmptyVector[Long] = parts.map(_._1)

  def applyRange(range: ByteRange): Option[NonEmptyVector[(Long, ByteRange)]] =
    range match
      case ByteRange.All => parts.map(e => (e._1, range)).some
      case r: ByteRange.Chunk =>
        NonEmptyVector.fromVector(applyRange0(parts.toVector, r, Vector.empty))

  @annotation.tailrec
  private def applyRange0(
      parts: Vector[(Long, ByteSize)],
      remain: ByteRange.Chunk,
      result: Vector[(Long, ByteRange)]
  ): Vector[(Long, ByteRange)] =
    if (remain.length == ByteSize.zero) result
    else
      parts match
        case (oid, len) +: t =>
          if (remain.offset >= len)
            applyRange0(t, remain.copy(offset = remain.offset - len), result)
          else {
            val pos = remain.offset + remain.length
            if (pos <= len) result :+ (oid, remain)
            else
              applyRange0(
                t,
                ByteRange.Chunk(ByteSize.zero, remain.length - (len - remain.offset)),
                result :+ (oid, ByteRange(remain.offset, len - remain.offset))
              )
          }

        case _ => result

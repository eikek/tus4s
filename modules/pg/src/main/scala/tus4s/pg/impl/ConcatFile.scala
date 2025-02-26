package tus4s.pg.impl

import cats.data.NonEmptyVector
import cats.syntax.all.*

import tus4s.core.data.ByteRange
import tus4s.core.data.ByteSize
import tus4s.core.data.UploadState

final case class ConcatFile(state: UploadState, parts: NonEmptyVector[(Long, ByteSize)]):

  def oids: NonEmptyVector[Long] = parts.map(_._1)

  def applyRange(range: ByteRange): Option[(ConcatFile, ByteRange)] =
    applyRange0(parts, range).map { case (p, r) => ConcatFile(state, p) -> r }

  @annotation.tailrec
  private def applyRange0(
      parts: NonEmptyVector[(Long, ByteSize)],
      r: ByteRange
  ): Option[(NonEmptyVector[(Long, ByteSize)], ByteRange)] = r match
    case ByteRange.All => (parts, r).some
    case ByteRange.Chunk(offset, length) =>
      val diff = parts.head._2 - offset
      if (diff > ByteSize.zero) (parts, r).some
      else
        NonEmptyVector.fromVector(parts.tail) match
          case Some(nev) => applyRange0(nev, ByteRange.Chunk(diff.abs, length))
          case None      => None

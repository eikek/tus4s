package tus4s.core.data

enum ByteRange:
  case All
  case Chunk(offset: ByteSize, length: ByteSize)

  def fold[A](ifAll: => A, ifChunk: Chunk => A): A = this match
    case All          => ifAll
    case chunk: Chunk => ifChunk(chunk)

  def includes(other: ByteRange): Boolean =
    (this, other) match
      case (Chunk(selfOff, selfLen), Chunk(off, len)) =>
        off >= selfOff && len <= selfLen
      case (All, _)           => true
      case (Chunk(_, _), All) => false

object ByteRange:
  val all: ByteRange = All
  val none: ByteRange.Chunk = ByteRange.Chunk(ByteSize.zero, ByteSize.zero)

  def bytes(offset: Long, length: Long): ByteRange =
    apply(ByteSize.bytes(offset), ByteSize.bytes(length))

  def apply(offset: ByteSize, length: ByteSize): ByteRange =
    Chunk(offset, length)

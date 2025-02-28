package tus4s.core.data

enum ByteRange:
  case All
  case Chunk(offset: ByteSize, length: Option[ByteSize])

  def isEmpty: Boolean =
    fold(false, _.length.exists(_ <= ByteSize.zero))

  def fold[A](ifAll: => A, ifChunk: Chunk => A): A = this match
    case All          => ifAll
    case chunk: Chunk => ifChunk(chunk)

object ByteRange:
  val all: ByteRange = All
  val none: ByteRange.Chunk = ByteRange.Chunk(ByteSize.zero, Some(ByteSize.zero))

  def from(offset: ByteSize): ByteRange =
    if (offset <= ByteSize.zero) ByteRange.all
    else ByteRange.Chunk(offset, None)

  def fromStartEnd(
      rangeStart: ByteSize,
      rangeEnd: ByteSize,
      endInclusive: Boolean = true
  ): ByteRange =
    val one = ByteSize.bytes(if (endInclusive) 1 else 0)
    ByteRange.Chunk(rangeStart, Some(rangeEnd - rangeStart + one))

  def bytes(offset: Long, length: Long): ByteRange =
    apply(ByteSize.bytes(offset), ByteSize.bytes(length))

  def apply(offset: ByteSize, length: ByteSize): ByteRange =
    Chunk(offset, Some(length))

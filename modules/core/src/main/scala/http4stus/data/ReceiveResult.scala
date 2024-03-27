package http4stus.data

enum ReceiveResult:
  case Success(offset: ByteSize)
  case OffsetMismatch(current: ByteSize)
  case NotFound

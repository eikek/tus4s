package http4stus.data

import java.time.Instant

enum ReceiveResult:
  case Success(offset: ByteSize, expires: Option[Instant])
  case OffsetMismatch(current: ByteSize)
  case UploadLengthMismatch
  case ChecksumMismatch
  case UploadIsFinal
  case NotFound

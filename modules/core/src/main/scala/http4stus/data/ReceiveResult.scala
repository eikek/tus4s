package http4stus.data

import java.time.Instant

enum ReceiveResult:
  case Success(offset: ByteSize, expires: Option[Instant])
  case OffsetMismatch(current: ByteSize)
  case UploadLengthMismatch
  case UploadDone
  case UploadIsFinal
  case ChecksumMismatch
  case UploadTooLarge(maxSize: ByteSize, current: ByteSize)
  case NotFound

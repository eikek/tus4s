package tus4s.data

import java.time.Instant

enum CreationResult:
  case Success(id: UploadId, offset: ByteSize, expires: Option[Instant])
  case ChecksumMismatch
  case UploadTooLarge(maxSize: ByteSize, current: ByteSize)

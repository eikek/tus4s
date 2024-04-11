package http4stus.data

import java.time.Instant

enum CreationResult:
  case Success(id: UploadId, offset: ByteSize, expires: Option[Instant])
  case ChecksumMismatch

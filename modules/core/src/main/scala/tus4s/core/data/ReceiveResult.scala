package tus4s.core.data

import java.time.Instant

enum ReceiveResult:
  case Success(offset: ByteSize, expires: Option[Instant])
  case OffsetMismatch(current: ByteSize)
  case UploadLengthMismatch(stateLen: ByteSize, reqLen: ByteSize)
  case UploadDone
  case UploadIsFinal
  case ChecksumMismatch
  case UploadTooLarge(maxSize: ByteSize, current: ByteSize)
  case NotFound

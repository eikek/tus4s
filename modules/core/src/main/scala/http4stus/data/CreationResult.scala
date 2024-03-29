package http4stus.data

enum CreationResult:
  case Success(id: UploadId, offset: ByteSize)

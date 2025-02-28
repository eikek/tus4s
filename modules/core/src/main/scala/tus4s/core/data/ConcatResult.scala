package tus4s.core.data

import cats.data.NonEmptyList

enum ConcatResult:
  case Success(id: UploadId)
  case PartsNotFound(ids: NonEmptyList[UploadId])

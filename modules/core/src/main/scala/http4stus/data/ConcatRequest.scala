package http4stus.data

import cats.data.NonEmptyList

final case class ConcatRequest(ids: NonEmptyList[UploadId], meta: MetadataMap)

package tus4s.core.data

import cats.data.NonEmptyList

final case class ConcatRequest(
    ids: NonEmptyList[UploadId],
    uris: NonEmptyList[Url],
    meta: MetadataMap
)

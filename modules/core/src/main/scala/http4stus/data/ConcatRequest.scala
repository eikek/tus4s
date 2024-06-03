package http4stus.data

import cats.data.NonEmptyList

import org.http4s.Uri

final case class ConcatRequest(
    ids: NonEmptyList[UploadId],
    uris: NonEmptyList[Uri],
    meta: MetadataMap
)

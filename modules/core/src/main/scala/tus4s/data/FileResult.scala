package tus4s.data

import fs2.Stream

import tus4s.data.MetadataMap.Key
import org.http4s.MediaType

final case class FileResult[F[_]](
    state: UploadState,
    data: Stream[F, Byte],
    hasContent: Boolean,
    contentType: Option[String],
    fileName: Option[String]
):

  def getContentType: Option[MediaType] =
    contentType
      .orElse(state.meta.getString(Key.contentType))
      .flatMap(ct => MediaType.parse(ct).toOption)

  def getFileName: Option[String] =
    fileName.orElse(state.meta.getString(Key.fileName))

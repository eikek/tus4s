package tus4s.core.data

import fs2.Stream

import tus4s.core.data.MetadataMap.Key

final case class FileResult[F[_]](
    state: UploadState,
    data: Stream[F, Byte],
    hasContent: Boolean,
    contentType: Option[String],
    fileName: Option[String]
):

  def getContentType: Option[String] =
    contentType
      .orElse(state.meta.getString(Key.contentType))

  def getFileName: Option[String] =
    fileName.orElse(state.meta.getString(Key.fileName))

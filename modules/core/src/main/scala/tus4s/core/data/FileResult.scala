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

  def getChecksum: Option[String] =
    state.meta.getString(Key.checksum)

  def modifyState(f: UploadState => UploadState): FileResult[F] =
    copy(state = f(state))

  def modifyMeta(f: MetadataMap => MetadataMap): FileResult[F] =
    modifyState(_.modifyMeta(f))

  def withFileName(name: String): FileResult[F] =
    copy(fileName = Some(name))

object FileResult:

  def empty[F[_]](id: UploadId): FileResult[F] =
    FileResult(UploadState(id), Stream.empty, false, None, None)

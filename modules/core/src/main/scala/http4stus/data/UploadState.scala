package http4stus.data

final case class UploadState(
  id: UploadId,
  offset: ByteSize = ByteSize.zero,
  length: Option[ByteSize] = None,
  meta: MetadataMap = MetadataMap.empty
):
  def isDone: Boolean = length.exists(_ == offset)

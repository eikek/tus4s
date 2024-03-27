package http4stus.data

final case class UploadState(
  id: UploadId,
  offset: ByteSize = ByteSize.zero,
  length: Option[ByteSize] = None
):
  def isDone: Boolean = length.exists(_ == offset)

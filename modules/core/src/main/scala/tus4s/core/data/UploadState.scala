package tus4s.core.data

final case class UploadState(
    id: UploadId,
    offset: ByteSize = ByteSize.zero,
    length: Option[ByteSize] = None,
    meta: MetadataMap = MetadataMap.empty,
    concatType: Option[ConcatType] = None
):
  def isDone: Boolean = length.exists(_ == offset)
  def isFinal: Boolean = concatType.exists(_.isFinal)
  def isPartial: Boolean = concatType.exists(_.isPartial)

  def withLength(len: ByteSize): UploadState =
    copy(length = Some(len))

  def withOffset(off: ByteSize): UploadState =
    copy(offset = off)

  def modifyMeta(f: MetadataMap => MetadataMap): UploadState =
    copy(meta = f(meta))

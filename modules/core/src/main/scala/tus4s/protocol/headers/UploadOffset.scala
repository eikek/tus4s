package tus4s.protocol.headers

import tus4s.data.ByteSize
import org.http4s.Header
import org.typelevel.ci.CIString

/** The Upload-Offset request and response header indicates a byte offset within a
  * resource. The value MUST be a non-negative integer.
  */
final case class UploadOffset(offset: ByteSize)

object UploadOffset:
  val name: CIString = CIString("Upload-Offset")

  val zero: UploadOffset = UploadOffset(ByteSize.zero)

  given Header[UploadOffset, Header.Single] =
    HeaderUtil.byteSizeHeader(name, _.offset, apply)

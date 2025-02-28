package tus4s.http4s.headers

import org.http4s.Header
import org.typelevel.ci.CIString
import tus4s.core.data.ByteSize

final case class UploadLength(length: ByteSize)

object UploadLength:
  val name: CIString = CIString("Upload-Length")

  given Header[UploadLength, Header.Single] =
    HeaderUtil.byteSizeHeader(name, _.length, apply)

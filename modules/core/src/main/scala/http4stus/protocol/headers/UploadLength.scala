package http4stus.protocol.headers

import http4stus.data.ByteSize
import org.http4s.Header
import org.typelevel.ci.CIString

final case class UploadLength(length: ByteSize)

object UploadLength:
  val name: CIString = CIString("Upload-Length")

  given Header[UploadLength, Header.Single] =
    HeaderUtil.byteSizeHeader(name, _.length, apply)

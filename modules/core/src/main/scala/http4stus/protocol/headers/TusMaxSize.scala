package http4stus.protocol.headers

import http4stus.data.ByteSize
import org.http4s.Header
import org.typelevel.ci.CIString

final case class TusMaxSize(length: ByteSize)

object TusMaxSize:
  val name: CIString = CIString("Tus-Max-Size")

  given Header[TusMaxSize, Header.Single] =
    HeaderUtil.byteSizeHeader(name, _.length, apply)

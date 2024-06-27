package tus4s.http4s.headers

import tus4s.core.data.ByteSize
import org.http4s.Header
import org.typelevel.ci.CIString

final case class TusMaxSize(length: ByteSize)

object TusMaxSize:
  val name: CIString = CIString("Tus-Max-Size")

  given Header[TusMaxSize, Header.Single] =
    HeaderUtil.byteSizeHeader(name, _.length, apply)

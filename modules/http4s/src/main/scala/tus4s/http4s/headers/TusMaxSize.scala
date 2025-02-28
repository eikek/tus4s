package tus4s.http4s.headers

import org.http4s.Header
import org.typelevel.ci.CIString
import tus4s.core.data.ByteSize

final case class TusMaxSize(length: ByteSize)

object TusMaxSize:
  val name: CIString = CIString("Tus-Max-Size")

  given Header[TusMaxSize, Header.Single] =
    HeaderUtil.byteSizeHeader(name, _.length, apply)

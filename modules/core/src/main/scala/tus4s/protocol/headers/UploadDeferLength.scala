package tus4s.protocol.headers

import org.http4s.Header
import org.http4s.ParseFailure
import org.typelevel.ci.CIString

final case class UploadDeferLength private (private val value: Int = 1)

object UploadDeferLength:
  val name: CIString = CIString("Upload-Defer-Length")

  val value: UploadDeferLength = UploadDeferLength()

  given Header[UploadDeferLength, Header.Single] =
    Header.create(
      name,
      _.value.toString,
      s =>
        s.toIntOption
          .filter(_ == 1)
          .toRight(ParseFailure(s"Invalid value for $name: $s", ""))
          .map(_ => value)
    )

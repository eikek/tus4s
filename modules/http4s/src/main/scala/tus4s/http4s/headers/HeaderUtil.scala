package tus4s.http4s.headers

import org.http4s.Header
import org.http4s.ParseFailure
import org.typelevel.ci.CIString
import tus4s.core.data.ByteSize

private[headers] object HeaderUtil:
  def byteSizeHeader[A](
      name: CIString,
      f: A => ByteSize,
      g: ByteSize => A
  ): Header[A, Header.Single] =
    Header.create(
      name,
      a => f(a).toBytes.toString,
      s =>
        ByteSize
          .fromString(s)
          .map(g)
          .left
          .map(err => ParseFailure(s"Invalid value for $name header: $s", err))
    )

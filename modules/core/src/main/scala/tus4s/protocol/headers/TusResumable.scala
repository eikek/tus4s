package tus4s.protocol.headers

import cats.syntax.all.*

import tus4s.data.Version
import org.http4s.Header
import org.http4s.ParseFailure
import org.typelevel.ci.CIString

final case class TusResumable(version: Version)

object TusResumable:
  val name: CIString = CIString("Tus-Resumable")

  val V1_0_0 = TusResumable(Version.V1_0_0)

  given Header[TusResumable, Header.Single] =
    Header.create(
      name,
      _.version.render,
      s =>
        Version
          .fromString(s)
          .leftMap(err => ParseFailure(s"Invalid value for header $name: $s", err))
          .map(TusResumable.apply)
    )

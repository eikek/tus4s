package http4stus.headers

import cats.syntax.all.*

import http4stus.Version
import org.http4s.Header
import org.http4s.ParseFailure
import org.typelevel.ci.CIString

final case class TusResumable(version: Version)

object TusResumable:
  val name: CIString = CIString("Tus-Resumable")

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

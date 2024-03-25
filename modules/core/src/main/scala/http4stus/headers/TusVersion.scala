package http4stus.headers

import cats.data.NonEmptyList
import cats.syntax.all.*

import http4stus.Version
import http4stus.internal.StringUtil
import org.http4s.Header
import org.http4s.ParseResult
import org.typelevel.ci.CIString

final case class TusVersion(versions: NonEmptyList[Version])

object TusVersion:
  val name: CIString = CIString("Tus-Version")

  given Header[TusVersion, Header.Single] =
    Header.create(
      name,
      _.versions.map(_.render).toList.mkString(", "),
      parse
    )

  private def parse(s: String): ParseResult[TusVersion] =
    StringUtil
      .commaListHeader(name, s, Version.fromString)
      .map(TusVersion.apply)

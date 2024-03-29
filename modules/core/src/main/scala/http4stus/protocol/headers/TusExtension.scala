package http4stus.protocol.headers

import cats.data.NonEmptyList
import cats.syntax.all.*

import http4stus.data.Extension
import http4stus.internal.StringUtil
import org.http4s.Header
import org.http4s.ParseFailure
import org.typelevel.ci.CIString

final case class TusExtension(extensions: NonEmptyList[Extension])

object TusExtension:
  val name: CIString = CIString("Tus-Extension")

  given Header[TusExtension, Header.Single] =
    Header.create(
      name,
      _.extensions.flatMap(_.names).toList.mkString(", "),
      s =>
        StringUtil
          .commaList(s)
          .toRight(s"No value for header $name")
          .flatMap(Extension.fromStrings)
          .leftMap(err => ParseFailure(s"Invalid value for header $name", err))
          .map(TusExtension.apply)
    )

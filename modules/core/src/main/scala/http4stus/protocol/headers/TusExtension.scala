package http4stus.protocol.headers

import cats.data.NonEmptyList
import cats.syntax.all.*

import http4stus.data.Extension
import org.http4s.Header
import org.http4s.ParseFailure
import org.typelevel.ci.CIString

final case class TusExtension(extensions: NonEmptyList[Extension])

object TusExtension:
  val name: CIString = CIString("Tus-Extension")

  given Header[TusExtension, Header.Single] =
    Header.create(
      name,
      _.extensions.map(_.name).toList.mkString(", "),
      s =>
        s.split(',')
          .toList
          .map(_.trim)
          .traverse(Extension.fromString)
          .flatMap(list =>
            NonEmptyList.fromList(list).toRight(s"No value for header $name")
          )
          .leftMap(err => ParseFailure(s"Invalid value for header $name: $s", err))
          .map(TusExtension.apply)
    )

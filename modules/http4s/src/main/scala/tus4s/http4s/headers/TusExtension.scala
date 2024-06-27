package tus4s.http4s.headers

import cats.data.NonEmptyList
import cats.syntax.all.*

import tus4s.core.data.Extension
import tus4s.core.internal.StringUtil
import org.http4s.Header
import org.http4s.ParseFailure
import org.typelevel.ci.CIString

final case class TusExtension(extensions: NonEmptyList[Extension]):
  lazy val findCreation: Option[Extension.Creation] =
    Extension.findCreation(extensions.toList.toSet)

  lazy val findChecksum: Option[Extension.Checksum] =
    Extension.findChecksum(extensions.toList.toSet)

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

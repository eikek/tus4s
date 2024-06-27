package tus4s.protocol.headers

import cats.data.NonEmptyList
import cats.syntax.all.*

import tus4s.data.Version
import tus4s.internal.StringUtil
import org.http4s.Header
import org.http4s.ParseResult
import org.typelevel.ci.CIString

final case class TusVersion(versions: NonEmptyList[Version])

object TusVersion:
  val name: CIString = CIString("Tus-Version")

  def apply(v: Version, vm: Version*): TusVersion =
    TusVersion(NonEmptyList(v, vm.toList))

  val V1_0_0 = apply(Version.V1_0_0)

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

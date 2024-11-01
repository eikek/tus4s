package tus4s.http4s.headers

import cats.data.NonEmptyList
import cats.syntax.all.*

import org.http4s.*
import org.typelevel.ci.CIString
import tus4s.core.data.*

final case class UploadConcat(concatType: ConcatType):
  def isPartial: Boolean = concatType match
    case ConcatType.Partial  => true
    case ConcatType.Final(_) => false

  def render: String = concatType.render

object UploadConcat:
  val name: CIString = CIString("Upload-Concat")

  def createFinal(uris: NonEmptyList[Uri]): UploadConcat =
    UploadConcat(ConcatType.Final(uris.map(u => Url(u.renderString))))

  given Header[UploadConcat, Header.Single] =
    Header.create(name, _.render, parse)

  def parse(s: String): ParseResult[UploadConcat] =
    ConcatType
      .fromString(s)
      .map(UploadConcat.apply)
      .leftMap(ParseFailure(_, ""))

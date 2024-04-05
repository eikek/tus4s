package http4stus.data

import cats.data.NonEmptyList
import cats.syntax.all.*

import http4stus.internal.StringUtil
import org.http4s.ParseFailure
import org.http4s.ParseResult
import org.http4s.Uri

enum ConcatType:
  case Final(partials: NonEmptyList[Uri])
  case Partial

object ConcatType:
  def fromString(s: String): ParseResult[ConcatType] =
    if ("partial".equalsIgnoreCase(s)) Right(Partial)
    else {
      val (fn, uriStr) = s.span(_ != ';')
      if (!fn.equalsIgnoreCase("final"))
        Left(ParseFailure(s"Invalid concat type: $s", ""))
      else
        StringUtil.spaceList(uriStr.drop(1).trim) match
          case None =>
            Left(ParseFailure(s"No partial upload uris given with final concat: $s", ""))
          case Some(nel) =>
            nel.traverse(Uri.fromString).map(Final.apply)
    }

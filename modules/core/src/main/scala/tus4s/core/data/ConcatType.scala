package tus4s.core.data

import cats.data.NonEmptyList
import cats.syntax.all.*

import tus4s.core.internal.StringUtil

enum ConcatType:
  case Final(partials: NonEmptyList[Url])
  case Partial

  def isFinal: Boolean = this != Partial
  def isPartial: Boolean = this == Partial

  def render: String = this match
    case ConcatType.Partial => "partial"
    case ConcatType.Final(uris) =>
      val list = uris.toList.map(_.asString).mkString(" ")
      s"final; $list"
 
object ConcatType:
  def fromString(s: String): Either[String, ConcatType] =
    if ("partial".equalsIgnoreCase(s)) Right(Partial)
    else
      val (fn, uriStr) = s.span(_ != ';')
      if (!fn.equalsIgnoreCase("final"))
        Left(s"Invalid concat type: $s")
      else
        StringUtil.spaceList(uriStr.drop(1).trim) match
          case None =>
            Left(s"No partial upload uris given with final concat: $s")
          case Some(nel) =>
            Right(ConcatType.Final(nel.map(Url.apply)))


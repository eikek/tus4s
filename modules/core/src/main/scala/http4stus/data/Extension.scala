package http4stus.data

import cats.data.NonEmptyList

import http4stus.internal.StringUtil

enum Extension:
  case Creation(options: Set[CreationOptions])
  case Expiration
  case Checksum
  case Termination
  case Concatenation

  val names: NonEmptyList[String] = this match
    case Creation(opts) =>
      val more = opts.toList.map(_.name)
      NonEmptyList("creation", more)
    case _ => NonEmptyList.one(StringUtil.camelToKebab(productPrefix))

object Extension:
  def fromStrings(str: NonEmptyList[String]): Either[String, NonEmptyList[Extension]] =
    ???

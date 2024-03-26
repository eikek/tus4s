package http4stus

import cats.data.NonEmptyList

import http4stus.internal.StringUtil

enum Extension:
  case Creation
  case CreationWithUpload
  case CreationDeferLength
  case Expiration
  case Checksum
  case Termination
  case Concatenation

  val name: String = StringUtil.camelToKebab(productPrefix)

object Extension:
  val all: NonEmptyList[Extension] =
    NonEmptyList.fromListUnsafe(Extension.values.toList)

  def fromString(s: String): Either[String, Extension] =
    Extension.values
      .find(_.name.equalsIgnoreCase(s))
      .toRight(s"Invalid tus extension name: $s")

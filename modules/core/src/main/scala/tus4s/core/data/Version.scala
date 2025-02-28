package tus4s.core.data

import cats.data.NonEmptyList

enum Version:
  case V1_0_0

  def render: String = this match
    case V1_0_0 => "1.0.0"

object Version:
  def fromString(s: String): Either[String, Version] =
    if (s == "1.0.0") Right(Version.V1_0_0)
    else Left(s"Unknown tus version: $s")

  val all: NonEmptyList[Version] =
    NonEmptyList.of(Version.V1_0_0).sortBy(_.ordinal)

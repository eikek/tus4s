package http4stus.data

import cats.data.NonEmptyList

enum ChecksumAlgorithm:
  case Sha1
  case Sha256
  case Sha512
  case Md5

  lazy val name: String = productPrefix.toLowerCase

object ChecksumAlgorithm:
  val all: NonEmptyList[ChecksumAlgorithm] =
    NonEmptyList.fromListUnsafe(ChecksumAlgorithm.values.toList)

  def fromString(s: String): Either[String, ChecksumAlgorithm] =
    ChecksumAlgorithm.values
      .find(_.name.equalsIgnoreCase(s))
      .toRight(s"Invalid checksum algorithm: $s")

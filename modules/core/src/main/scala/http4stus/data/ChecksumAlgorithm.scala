package http4stus.data

enum ChecksumAlgorithm:
  case Sha1
  case Sha256
  case Sha512
  case Md5

  lazy val name: String = productPrefix.toLowerCase

object ChecksumAlgorithm:

  def fromString(s: String): Either[String, ChecksumAlgorithm] =
    ChecksumAlgorithm.values
      .find(_.name.equalsIgnoreCase(s))
      .toRight(s"Invalid checksum algorithm: $s")

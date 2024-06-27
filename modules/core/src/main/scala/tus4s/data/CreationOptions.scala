package tus4s.data

enum CreationOptions:
  case WithUpload
  case WithDeferredLength

  lazy val name: String = this match
    case WithUpload         => "creation-with-upload"
    case WithDeferredLength => "creation-defer-length"

object CreationOptions:
  val all: Set[CreationOptions] = CreationOptions.values.toSet

  def fromString(s: String): Either[String, CreationOptions] =
    CreationOptions.values
      .find(_.name.equalsIgnoreCase(s))
      .toRight(s"Invalid extension name: $s")

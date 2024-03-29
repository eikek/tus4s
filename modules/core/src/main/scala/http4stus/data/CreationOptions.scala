package http4stus.data

enum CreationOptions:
  case WithUpload
  case WithDeferredLength

  val name: String = this match
    case WithUpload => "creation-with-upload"
    case WithDeferredLength => "creation-defer-length"

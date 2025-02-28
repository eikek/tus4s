package tus4s.core.data

opaque type Url = String

object Url:
  def apply(url: String): Url = url

  extension (self: Url) def asString: String = self

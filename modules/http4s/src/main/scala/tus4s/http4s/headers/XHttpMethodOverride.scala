package tus4s.http4s.headers

import org.http4s.Header
import org.http4s.Method
import org.typelevel.ci.CIString

final case class XHttpMethodOverride(method: Method)

object XHttpMethodOverride:
  val name: CIString = CIString("X-HTTP-Method-Override")

  given Header[XHttpMethodOverride, Header.Single] =
    Header.create(
      name,
      _.method.name,
      s => Method.fromString(s).map(XHttpMethodOverride.apply)
    )

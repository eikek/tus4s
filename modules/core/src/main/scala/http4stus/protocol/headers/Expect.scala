package http4stus.protocol.headers

import org.typelevel.ci.CIString
import org.http4s.*

final case class Expect(expectation: Expect.Expectation):
  def render: String = expectation.value

object Expect:
  val name: CIString = CIString("Expect")

  enum Expectation(val value: String):
    case Continue extends Expectation("100-continue")

  given Header[Expect, Header.Single] =
    Header.create(name, _.render, parse)

  def parse(s: String): ParseResult[Expect] =
    Expectation.values
      .find(_.value.equalsIgnoreCase(s))
      .map(Expect.apply)
      .toRight(ParseFailure(s"Invalid expectation: $s", ""))

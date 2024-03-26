package http4stus

import cats.*
import cats.parse.{Numbers, Parser as P}
import cats.syntax.all.*

opaque type ByteSize = Long

object ByteSize:
  val zero: ByteSize = 0

  def bytes(n: Long): ByteSize =
    require(n >= 0, "A size in bytes must be non-negative")
    n

  def kb(n: Long): ByteSize =
    bytes(n * 1024)

  def mb(n: Long): ByteSize =
    kb(n * 1024)

  def fromString(s: String): Either[String, ByteSize] =
    Parser.size.parseAll(s).leftMap(_.show)

  extension (self: ByteSize)
    def toBytes: Long = self
    def toKb: Double = self.toDouble / 1024d
    def toMb: Double = toKb / 1024
    def +(other: ByteSize): ByteSize = self + other

  given Show[ByteSize] = Show.show { n =>
    n.toMb match
      case m if m >= 1 => f"$m%.2fM"
      case _ =>
        n.toKb match
          case k if k >= 1 => f"$k%.2fK"
          case _           => s"$n bytes"
  }

  given Monoid[ByteSize] = Monoid.instance(zero, _ + _)
  given Order[ByteSize] = Order.fromLessThan(_ < _)
  given Eq[ByteSize] = Eq.fromUniversalEquals

  private object Parser {
    val kb: P[Int] = P.charIn('k', 'K').as(1024)
    val mb: P[Int] = P.charIn('m', 'M').as(1024 * 1024)
    val unit: P[Int] = kb | mb
    val ws = P.charsWhile0(_.isWhitespace).void

    val size = (Numbers.nonNegativeIntString.map(_.toLong) ~ (ws *> unit).?).map {
      case (n, f) =>
        ByteSize.bytes((n * f.getOrElse(1)).toLong)
    }
  }

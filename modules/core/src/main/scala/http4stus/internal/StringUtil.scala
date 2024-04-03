package http4stus.internal

import cats.data.NonEmptyList

import org.http4s.ParseFailure
import org.typelevel.ci.CIString

private[http4stus] object StringUtil:
  def lowerFirst(s: String): String =
    if (s.isEmpty || s.charAt(0).isLower) s
    else {
      val chars = s.toCharArray()
      chars(0) = chars(0).toLower
      new String(chars)
    }

  def camelSplit(s: String): Vector[String] =
    def go(remain: String, result: Vector[String]): Vector[String] =
      if (remain.isEmpty) result
      else {
        val (h, t) = remain.span(_.isLower)
        go(lowerFirst(t), if (h.isEmpty) result else result :+ h)
      }
    go(s, Vector.empty)

  def camelToKebab(s: String): String =
    camelSplit(s).mkString("-")

  def commaList(s: String): Option[NonEmptyList[String]] =
    NonEmptyList.fromList(s.split(',').toList.map(_.trim))

  def spaceList(s: String): Option[NonEmptyList[String]] =
    NonEmptyList.fromList(s.split(' ').toList.map(_.trim))

  def commaListHeader[A](
      name: CIString,
      value: String,
      single: String => Either[String, A]
  ): Either[ParseFailure, NonEmptyList[A]] =
    commaList(value)
      .toRight(s"No value for header $name")
      .flatMap(_.traverse(single))
      .left
      .map(err => ParseFailure(s"Invalid value for header $name: $value", err))

  def pair(s: String, sep: Char): Either[String, (String, String)] =
    s.split(sep).toList match
      case a :: b :: Nil => Right(a -> b)
      case a :: Nil      => Right(a -> "")
      case _             => Left(s"No key-value pair: $s")

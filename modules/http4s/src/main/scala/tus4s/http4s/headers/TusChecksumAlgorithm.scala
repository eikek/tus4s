package tus4s.http4s.headers

import cats.data.NonEmptyList

import org.http4s.Header
import org.http4s.ParseResult
import org.typelevel.ci.CIString
import tus4s.core.data.*
import tus4s.http4s.Headers

final case class TusChecksumAlgorithm(algorithms: NonEmptyList[ChecksumAlgorithm]):
  def render: String = algorithms.map(_.name).toList.mkString(",")

object TusChecksumAlgorithm:
  val name: CIString = CIString("Tus-Checksum-Algorithm")

  given Header[TusChecksumAlgorithm, Header.Single] =
    Header.create(name, _.render, parse)

  def parse(s: String): ParseResult[TusChecksumAlgorithm] =
    Headers
      .commaListHeader(name, s, ChecksumAlgorithm.fromString)
      .map(TusChecksumAlgorithm.apply)

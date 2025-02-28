package tus4s.http4s.headers

import org.http4s.*
import org.http4s.Header
import org.typelevel.ci.CIString
import scodec.bits.ByteVector
import tus4s.core.data.*

final case class UploadChecksum(algorithm: ChecksumAlgorithm, checksum: ByteVector):
  def render: String =
    s"${algorithm.name} ${checksum.toBase64}"

  def toModel: UploadRequest.Checksum =
    UploadRequest.Checksum(algorithm, checksum)

object UploadChecksum:
  val name: CIString = CIString("Upload-Checksum")

  given Header[UploadChecksum, Header.Single] =
    Header.create(name, _.render, parse)

  def parse(s: String): ParseResult[UploadChecksum] =
    val (algoStr, checksumStr) = s.span(_ != ' ')
    (for {
      algo <- ChecksumAlgorithm.fromString(algoStr)
      sum <- ByteVector.fromBase64Descriptive(checksumStr)
    } yield UploadChecksum(algo, sum)).left.map(err => ParseFailure(err, ""))

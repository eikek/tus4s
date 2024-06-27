package tus4s.http4s.server

import cats.syntax.all.*

import tus4s.core.data.ByteSize
import tus4s.core.data.ChecksumAlgorithm
import tus4s.core.data.Extension
import org.http4s.*
import org.typelevel.ci.CIString

trait TusDecodeFailure extends DecodeFailure:
  def cause: Option[Throwable] = None

object TusDecodeFailure:

  final case class MaxSizeExceeded(maxSize: ByteSize, size: ByteSize)
      extends TusDecodeFailure:
    val message: String =
      if (maxSize == size) show"Upload exceeds $maxSize"
      else show"Upload of $size exceeds max size of $maxSize"
    def toHttpResponse[F[_]](httpVersion: HttpVersion): Response[F] =
      Response(Status.PayloadTooLarge, httpVersion)
        .withEntity(show"The upload of $size is too large, exceeding $maxSize.")(
          EntityEncoder.stringEncoder[F]
        )

  final case class MissingHeader(name: CIString) extends TusDecodeFailure:
    val message: String = s"Header $name expected, but was missing in the request"
    def toHttpResponse[F[_]](httpVersion: HttpVersion): Response[F] =
      Response(Status.BadRequest, httpVersion)
        .withEntity(message)(EntityEncoder.stringEncoder[F])

  final case class UnsupportedChecksumAlgorithm(algo: ChecksumAlgorithm)
      extends TusDecodeFailure:
    val message: String = s"Checksum algorithm $algo is not supported"
    def toHttpResponse[F[_]](httpVersion: HttpVersion): Response[F] =
      Response(Status.BadRequest, httpVersion)
        .withEntity(message)(EntityEncoder.stringEncoder[F])

  final case class UnsupportedExtension(ext: Extension) extends TusDecodeFailure:
    val message: String =
      show"The server doesn't support the extension or extension variant: $ext"
    def toHttpResponse[F[_]](httpVersion: HttpVersion): Response[F] =
      Response(Status.UnprocessableEntity, httpVersion)
        .withEntity(message)(EntityEncoder.stringEncoder[F])

  final case class PartialUriError(msg: String) extends TusDecodeFailure:
    val message: String = show"There was an error resolving partial uris: $msg"
    def toHttpResponse[F[_]](httpVersion: HttpVersion): Response[F] =
      Response(Status.UnprocessableEntity, httpVersion)
        .withEntity(message)(EntityEncoder.stringEncoder[F])

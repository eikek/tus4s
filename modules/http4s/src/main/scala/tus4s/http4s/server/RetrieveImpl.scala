package tus4s.http4s.server

import cats.syntax.all.*

import org.http4s.*
import org.http4s.headers.*
import org.http4s.headers.Range.SubRange
import org.typelevel.ci.*
import tus4s.core.data.ByteRange
import tus4s.core.data.ByteSize
import tus4s.core.data.FileResult
import tus4s.http4s.headers.*

private object RetrieveImpl:

  def get[F[_]](file: FileResult[F]): Response[F] =
    Response(body = file.data, headers = basicHeaders(file))

  def range[F[_]](file: FileResult[F], range: ByteRange) =
    val ar = `Accept-Ranges`(RangeUnit.Bytes)
    range match
      case ByteRange.All =>
        get(file).putHeaders(ar)

      case ByteRange.Chunk(offset, lenOpt) =>
        if (file.state.length.exists(_ < offset))
          Response(status = Status.RangeNotSatisfiable)
        else
          val clen = lenOpt.orElse(file.state.length.map(_ - offset))
          val cr = clen
            .map(len => `Content-Range`(offset.toBytes, (offset + len).toBytes - 1))
            .getOrElse(`Content-Range`(offset.toBytes))
            .copy(length = file.state.length.map(_.toBytes))

          Response(
            status = Status.PartialContent,
            body = file.data,
            headers = basicHeaders(file).put(ar, cr)
          ).withContentLength(clen)

  def getETag[F[_]](req: Request[F], file: FileResult[F]): Option[Response[F]] =
    val actual = req.headers.get[`If-None-Match`].flatMap(_.tags).map(_.head.tag)
    val expect = file.getChecksum
    val notModified = (actual, expect).mapN(_ == _).exists(identity)
    Option.when(notModified)(
      Response(status = Status.NotModified, headers = basicHeaders(file))
    )

  /** Get the byte-range from the request headers. */
  def getRange[F[_]](req: Request[F]): Either[Response[F], Option[ByteRange]] =
    req.headers.get[Range].map(_.ranges.head) match
      case None => Right(None)
      case Some(SubRange(first, None)) if first < 0 =>
        Left(Response(status = Status.RangeNotSatisfiable))

      case Some(SubRange(first, None)) =>
        Right(ByteRange.from(ByteSize.bytes(first)).some)

      case Some(SubRange(first, Some(second))) if first < 0 || first > second =>
        Left(Response(status = Status.RangeNotSatisfiable))

      case Some(SubRange(first, Some(second))) =>
        Right(ByteRange.fromStartEnd(ByteSize.bytes(first), ByteSize.bytes(second)).some)

  private def basicHeaders[F[_]](file: FileResult[F]): Headers =
    Headers(
      Option.when(file.state.meta.nonEmpty)(UploadMetadata(file.state.meta)),
      UploadLength(file.state.length.getOrElse(ByteSize.zero)),
      file.getContentType
        .flatMap(ct => MediaType.parse(ct).toOption)
        .map(`Content-Type`(_)),
      file.getFileName
        .map(n => `Content-Disposition`("inline", Map(ci"filename" -> n))),
      file.getChecksum.map(cs => ETag(EntityTag(cs)))
    )

  extension [F[_]](self: Response[F])
    def withContentLength(size: Option[ByteSize]): Response[F] =
      size.map(sz => self.putHeaders(`Content-Length`(sz.toBytes))).getOrElse(self)

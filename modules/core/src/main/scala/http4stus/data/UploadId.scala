package http4stus.data

import java.util.UUID

import cats.effect.Sync

import org.http4s.Uri

opaque type UploadId = String

object UploadId:
  private val validChars =
    (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') ++ "-._~").toSet

  def fromString(s: String): Either[String, UploadId] =
    if (s.nonEmpty && s.forall(validChars.contains)) Right(s)
    else Left(s"Only url-safe characters allowed for upload id: $s")

  def unsafeFromString(s: String): UploadId =
    fromString(s).fold(sys.error, identity)

  def randomUUID[F[_]: Sync]: F[UploadId] =
    Sync[F].delay(unsafeFromString(UUID.randomUUID().toString))

  def unapply(s: String): Option[UploadId] =
    fromString(s).toOption

  given Uri.Path.SegmentEncoder[UploadId] =
    Uri.Path.SegmentEncoder.instance(a => Uri.Path.Segment(a))

  extension (self: UploadId) def value: String = self

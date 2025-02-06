package tus4s.core.data

import cats.effect.Sync
import wvlet.airframe.ulid.ULID
import java.util.UUID

opaque type UploadId = String

object UploadId:
  private val validChars =
    (('A' to 'Z') ++ ('a' to 'z') ++ ('0' to '9') ++ "-._~").toSet

  def fromString(s: String): Either[String, UploadId] =
    if (s.nonEmpty && s.forall(validChars.contains)) Right(s)
    else Left(s"Only url-safe characters allowed for upload id: $s")

  def unsafeFromString(s: String): UploadId =
    fromString(s).fold(sys.error, identity)

  def randomULID[F[_]: Sync]: F[UploadId] =
    Sync[F].delay(fromULID(ULID.newULID))

  def fromULID(ulid: ULID): UploadId =
    unsafeFromString(ulid.toString)

  def fromUUID(uuid: UUID): UploadId =
    unsafeFromString(uuid.toString())

  def unapply(s: String): Option[UploadId] =
    fromString(s).toOption

  extension (self: UploadId) def value: String = self

package http4stus.fs

import fs2.io.file.Path
import fs2.Stream
import http4stus.data.UploadId
import fs2.io.file.Files
import cats.Functor
import cats.syntax.all.*
import http4stus.data.UploadState

private final case class UploadEntry(
  dir: Path
):

  def readState[F[_]: Files]: F[UploadState] = ???

  def writeChunk[F[_]: Files](data: Stream[F, Byte]): F[TempChunk] = ???

private object UploadEntry:

  def find[F[_]: Files: Functor](dir: Path, id: UploadId): F[Option[UploadEntry]] =
    val entryDir = dir / id.value
    Files[F].exists(entryDir).map {
      case true => Some(UploadEntry(entryDir))
      case false => None
    }

  def delete[F[_]: Files: Functor](dir: Path, id: UploadId): F[Unit] =
    Files[F].deleteRecursively(dir / id.value)

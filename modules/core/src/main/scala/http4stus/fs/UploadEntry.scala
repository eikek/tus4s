package http4stus.fs

import cats.Functor
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Files
import fs2.io.file.Path

import http4stus.data.ChecksumAlgorithm
import http4stus.data.MetadataMap
import http4stus.data.UploadId
import http4stus.data.UploadState
import scodec.bits.ByteVector

final private case class UploadEntry(
    id: UploadId,
    dir: Path
):

  def readState[F[_]: Files]: F[UploadState] = ???

  def writeChunk[F[_]: Files](data: Stream[F, Byte]): F[TempChunk] = ???

  def createChecksum[F[_]: Files](
      temp: TempChunk,
      algo: ChecksumAlgorithm
  ): F[ByteVector] =
    ???

  def append[F[_]: Files](temp: TempChunk, state: UploadState): F[Unit] = ???

  def writeState[F[_]: Files](state: UploadState): F[Unit] = ???

  def copyAll[F[_]: Files](parts: List[UploadEntry], meta: MetadataMap): F[UploadState] =
    ???

private object UploadEntry:

  def find[F[_]: Files: Functor](dir: Path, id: UploadId): F[Option[UploadEntry]] =
    val entryDir = dir / id.value
    Files[F].exists(entryDir).map {
      case true  => Some(UploadEntry(id, entryDir))
      case false => None
    }

  def create[F[_]: Files: Functor](dir: Path, id: UploadId): F[UploadEntry] =
    val entryDir = dir / id.value
    Files[F].createDirectories(entryDir).as(UploadEntry(id, entryDir))

  def delete[F[_]: Files: Functor](dir: Path, id: UploadId): F[Unit] =
    Files[F].deleteRecursively(dir / id.value)

package tus4s.fs

import cats.Functor
import cats.data.NonEmptyList
import cats.data.OptionT
import cats.effect.{Resource, Sync}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.{Files, Path}
import fs2.io.file.{Flag, Flags}

import tus4s.core.HashSupport
import tus4s.core.data.*

final private case class UploadEntry(
    id: UploadId,
    dir: Path
):
  private val stateFile = dir / "state"
  private[fs] val dataFile = dir / "file"

  def readState[F[_]: Files: Sync]: F[UploadState] =
    Files[F]
      .exists(stateFile)
      .flatMap:
        case false => UploadState(id).pure[F]
        case true =>
          Files[F]
            .readUtf8Lines(stateFile)
            .through(StateCodec.fromLines[F](id))
            .compile
            .lastOrError

  def writeChunk[F[_]: Files: Sync](
      algo: Option[ChecksumAlgorithm],
      data: Stream[F, Byte]
  ): Resource[F, TempChunk] =
    val create = UploadId.randomULID[F].flatMap { id =>
      val file = dir / id.value
      HashSupport
        .hasher[F](algo)
        .use { hasher =>
          val writer = hasher.observe(Files[F].writeAll(file))
          data.through(writer).compile.lastOrError
        }
        .flatMap(hash =>
          Files[F].size(file).map(n => TempChunk(id.value, ByteSize.bytes(n), hash))
        )
    }
    Resource.make(create)(tc => Files[F].deleteIfExists(dir / tc.id).void)

  def append[F[_]: Files: Sync](temp: TempChunk, state: UploadState): F[Unit] =
    val src = dir / temp.id
    val target = dataFile
    val flags = Flags(Flag.Append, Flag.Create)
    Files[F]
      .readAll(src)
      .through(Files[F].writeAll(target, flags))
      .compile
      .drain >> Files[F].delete(src)

  def writeState[F[_]: Files: Sync](state: UploadState): F[Unit] =
    StateCodec.toBytes(state).through(Files[F].writeAll(stateFile)).compile.drain

private object UploadEntry:

  def find[F[_]: Files: Functor](dir: Path, id: UploadId): F[Option[UploadEntry]] =
    val entryDir = dir / id.value
    Files[F]
      .exists(entryDir)
      .map:
        case true  => Some(UploadEntry(id, entryDir))
        case false => None

  def findWithState[F[_]: Files: Sync](
      dir: Path,
      id: UploadId
  ): F[Option[(UploadEntry, UploadState)]] =
    OptionT(find(dir, id)).semiflatMap(e => e.readState.map(s => (e, s))).value

  def create[F[_]: Files: Functor](dir: Path, id: UploadId): F[UploadEntry] =
    val entryDir = dir / id.value
    Files[F].createDirectories(entryDir).as(UploadEntry(id, entryDir))

  def delete[F[_]: Files: Functor](dir: Path, id: UploadId): F[Unit] =
    Files[F].deleteRecursively(dir / id.value)

  def copyAll[F[_]: Files: Sync](
      dir: Path,
      parts: List[UploadEntry],
      uris: NonEmptyList[Url],
      meta: MetadataMap
  ): F[UploadState] =
    UploadId.randomULID[F].flatMap(create(dir, _)).flatMap { targetEntry =>
      val flags = Flags(Flag.Append, Flag.Create)
      val state = Files[F]
        .size(targetEntry.dataFile)
        .map(ByteSize.bytes)
        .map(len =>
          UploadState(targetEntry.id, len, len.some, meta, ConcatType.Final(uris).some)
        )
      Stream
        .emits(parts)
        .foreach(entry =>
          Files[F]
            .readAll(entry.dataFile)
            .through(Files[F].writeAll(targetEntry.dataFile, flags))
            .compile
            .drain
        )
        .compile
        .drain >> state.flatTap(targetEntry.writeState)
    }

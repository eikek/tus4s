package tus4s.pg

import cats.data.OptionT
import cats.effect.*
import cats.syntax.all.*
import fs2.Chunk
import fs2.Stream

import org.postgresql.largeobject.LargeObject
import tus4s.core.data.*
import tus4s.core.data.UploadRequest
import tus4s.pg.impl.{DbTask, DbTaskS}

private[pg] class PgTasks[F[_]: Sync](table: String):

  val fileTable = PgTusTable[F](table)

  def createUpload(req: UploadRequest[F], chunkSize: Int): DbTask[F, CreationResult] =
    for
      id <- DbTask.liftF(UploadId.randomULID[F])
      stateNoContent = UploadState(
        id,
        ByteSize.zero,
        req.uploadLength,
        req.meta,
        Option.when(req.isPartial)(ConcatType.Partial)
      )
      (oid, off) <-
        if (req.hasContent) insertFile(req.data.chunkN(chunkSize))
        else DbTask.pure((0L, stateNoContent.offset))
      _ <- fileTable.insert(
        stateNoContent.copy(offset = off),
        oid = Some(oid).filter(_ != 0)
      )
      res = CreationResult.Success(id, off, None)
    yield res

  def insertFile(data: Stream[F, Chunk[Byte]]) =
    for
      lom <- DbTask.loManager[F]
      oid <- DbTask.createLargeObject(lom)
      size <- DbTask
        .openLoWrite(lom, oid)
        .mapF(_.use { obj =>
          data
            .evalMap(chunk =>
              Sync[F].blocking {
                val bs = chunk.toArraySlice
                obj.write(bs.values, bs.offset, bs.size)
                chunk.size.toLong
              }
            )
            .compile
            .foldMonoid
        })
    yield (oid, ByteSize.bytes(size))

  def findFile(
      id: UploadId,
      chunkSize: Int,
      range: ByteRange = ByteRange.All
  ): DbTask[F, Option[FileResult[F]]] =
    (for
      stateOpt <- fileTable.find(id).mapF(OptionT.apply)
      (state, oidOpt) = stateOpt
      data = oidOpt match
        case Some(oid) => loadFile(oid, range, chunkSize)
        case None      => DbTask.liftF(Stream.empty)

      res <- DbTask
        .delay(conn => FileResult(state, data.run(conn), oidOpt.isDefined, None, None))
        .mapF(OptionT.liftF)
    yield res).mapF(_.value)

  def loadFile(oid: Long, range: ByteRange, chunkSize: Int): DbTaskS[F, Byte] =
    for
      lom <- DbTask.loManager.mapF(Stream.eval)
      lo <- DbTask.openLoRead(lom, oid).mapF(Stream.resource)
      _ <- DbTask.seek(lo, range).mapF(Stream.eval)
      res <- DbTask.liftF {
        Stream
          .eval(
            readNextChunk(
              lo,
              range.fold(0L, _.offset.toBytes),
              range.fold(None, _.length.toBytes.some),
              chunkSize
            )
          )
          .repeat
          .takeThrough(_.size == chunkSize)
          .flatMap(Stream.chunk)
      }
    yield res

  def readNextChunk(
      lo: LargeObject,
      offset: Long,
      size: Option[Long],
      chunkSize: Int
  ): F[Chunk[Byte]] =
    for
      pos <- Sync[F].blocking(lo.tell64())
      bytesRead = pos - offset
      remaining = math.min(
        chunkSize,
        size.map(_ - bytesRead).map(_.toInt).getOrElse(chunkSize)
      )
      next <-
        if (size.exists(_ <= bytesRead)) Chunk.empty.pure[F]
        else Sync[F].blocking(Chunk.array(lo.read(remaining)))
    yield next

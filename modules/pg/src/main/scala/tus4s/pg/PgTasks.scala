package tus4s.pg

import cats.data.OptionT
import cats.effect.*
import cats.syntax.all.*
import fs2.Chunk
import fs2.Stream

import org.postgresql.largeobject.LargeObject
import org.postgresql.largeobject.LargeObjectManager
import tus4s.core.data.*
import tus4s.core.data.UploadRequest
import tus4s.pg.impl.syntax.*
import tus4s.pg.impl.{DbTask, DbTaskS}

private[pg] class PgTasks[F[_]: Sync](table: String):

  val fileTable = PgTusTable[F](table)

  def createUpload(
      req: UploadRequest[F],
      chunkSize: ByteSize,
      maxSize: Option[ByteSize]
  ): DbTask[F, CreationResult] =
    for
      id <- DbTask.liftF(UploadId.randomULID[F])
      _ <- fileTable.insert(req.toStateNoContent(id)).inTx

      saved <-
        if (req.hasContent)
          insertFile(id, req.data.chunkN(chunkSize.toBytes.toInt), maxSize)
        else DbTask.pure(Right(ByteSize.zero))
      res = saved.fold(identity, off => CreationResult.Success(id, off, None))
    yield res

  def insertFile(
      id: UploadId,
      data: Stream[F, Chunk[Byte]],
      maxSize: Option[ByteSize]
  ): DbTask[F, Either[CreationResult, ByteSize]] =
    for
      lom <- DbTask.loManager[F]
      oid <- DbTask
        .createLargeObject(lom)
        .flatTap(oid => fileTable.updateOid(id, oid))
        .inTx

      size <- insertSafe(id, data, oid, lom, maxSize)
      res = maxSize match
        case Some(ms) if ms.toBytes >= size =>
          Left(CreationResult.UploadTooLarge(ms, ByteSize.bytes(size)))
        case _ => Right(ByteSize.bytes(size))
    yield res

  def insertSafe(
      id: UploadId,
      data: Stream[F, Chunk[Byte]],
      oid: Long,
      lom: LargeObjectManager,
      maxSize: Option[ByteSize]
  ): DbTask[F, Long] =
    DbTask { conn =>
      data
        .evalScan(0L) { (acc, chunk) =>
          insertChunk(id, lom, oid, chunk)(acc).inTx.run(conn)
        }
        .takeWhile(size => maxSize.forall(_.toBytes > size))
        .compile
        .lastOrError
    }

  def insertChunk(
      id: UploadId,
      lom: LargeObjectManager,
      oid: Long,
      chunk: Chunk[Byte]
  )(pos: Long): DbTask[F, Long] =
    (for
      obj <- DbTask.openLoWriteR(lom, oid)
      _ <- DbTask.delay { _ =>
        val bs = chunk.toArraySlice
        obj.seek64(pos, LargeObject.SEEK_SET)
        obj.write(bs.values, bs.offset, bs.size)
      }.resource
      nextOffset = pos + chunk.size
      _ <- fileTable.updateOffset(id, ByteSize.bytes(nextOffset)).resource
    yield nextOffset).evaluated

  def findFile(
      id: UploadId,
      chunkSize: ByteSize,
      makeConn: ConnectionResource[F],
      range: ByteRange = ByteRange.All
  ): DbTask[F, Option[FileResult[F]]] =
    (for
      stateOpt <- fileTable.find(id).mapF(OptionT.apply)
      (state, oidOpt) = stateOpt
      data = oidOpt match
        case Some(oid) =>
          Stream
            .resource(makeConn)
            .flatMap(loadFile(oid, range, chunkSize.toBytes.toInt).run)
        case None => Stream.empty

      res = FileResult(state, data, oidOpt.isDefined, None, None)
    yield res).mapF(_.value)

  def loadFile(oid: Long, range: ByteRange, chunkSize: Int): DbTaskS[F, Byte] =
    for
      lom <- DbTask.loManager.mapF(Stream.eval)
      _ <- DbTask.withAutoCommit(false).mapF(Stream.resource)
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

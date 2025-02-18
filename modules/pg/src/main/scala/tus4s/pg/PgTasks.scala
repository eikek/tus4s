package tus4s.pg

import cats.data.NonEmptyList
import cats.data.OptionT
import cats.effect.*
import cats.syntax.all.*
import fs2.Chunk
import fs2.Stream

import org.postgresql.largeobject.LargeObject
import org.postgresql.largeobject.LargeObjectManager
import tus4s.core.data.*
import tus4s.core.internal.Validation
import tus4s.pg.impl.syntax.*
import tus4s.pg.impl.{DbTask, DbTaskS}

private[pg] class PgTasks[F[_]: Sync](table: String):

  val fileTable = PgTusTable[F](table)

  def createUpload(
      req: UploadRequest[F],
      chunkSize: ByteSize,
      maxSize: Option[ByteSize]
  ): DbTask[F, CreationResult] =
    Validation.validateCreate(req, maxSize) match
      case Some(r) => DbTask.pure(r)
      case None =>
        for
          id <- DbTask.liftF(UploadId.randomULID[F])
          _ <- fileTable.insert(req.toStateNoContent(id)).inTx

          saved <-
            if (req.hasContent)
              insertFile(id, req.dataChunked(chunkSize, maxSize), maxSize)
            else DbTask.pure(ByteSize.zero)

          res = Validation
            .validateCreate(req.copy(uploadLength = saved.some), maxSize)
            .getOrElse(CreationResult.Success(id, saved, None))
        yield res

  def receiveChunk(
      id: UploadId,
      req: UploadRequest[F],
      chunkSize: ByteSize,
      maxSize: Option[ByteSize]
  ): DbTask[F, ReceiveResult] =
    (for
      (state, oidOpt) <- fileTable.find(id).mapF(OptionT.apply)
      res <- Validation
        .validateReceive(state, req, maxSize)
        .map(DbTask.pure[F, ReceiveResult])
        .getOrElse {
          receiveChunk0(id, oidOpt, state, req.dataChunked(chunkSize, maxSize)).map(
            size =>
              Validation
                .validateReceive(state, req.copy(contentLength = size.some), maxSize)
                .getOrElse(ReceiveResult.Success(size, None))
          )
        }
        .mapF(OptionT.liftF)
      _ <- req.uploadLength
        .map(fileTable.updateLength(id, _))
        .getOrElse(DbTask.pure(0))
        .mapF(OptionT.liftF)
    yield res).mapF(_.getOrElse(ReceiveResult.NotFound))

  def receiveChunk0(
      id: UploadId,
      oidOpt: Option[Long],
      state: UploadState,
      data: Stream[F, Chunk[Byte]]
  ): DbTask[F, ByteSize] =
    for
      lom <- DbTask.loManager[F]
      oid <- oidOpt.map(DbTask.pure).getOrElse {
        DbTask
          .createLargeObject(lom)
          .flatTap(oid => fileTable.updateOid(id, oid))
          .inTx
      }
      size <- insertSafe(id, data, oid, lom, state.offset)
    yield size

  def insertFile(
      id: UploadId,
      data: Stream[F, Chunk[Byte]],
      maxSize: Option[ByteSize]
  ): DbTask[F, ByteSize] =
    for
      lom <- DbTask.loManager[F]
      oid <- DbTask
        .createLargeObject(lom)
        .flatTap(oid => fileTable.updateOid(id, oid))
        .inTx

      size <- insertSafe(id, data, oid, lom, ByteSize.zero)
    yield size

  def insertSafe(
      id: UploadId,
      data: Stream[F, Chunk[Byte]],
      oid: Long,
      lom: LargeObjectManager,
      pos: ByteSize
  ): DbTask[F, ByteSize] =
    DbTask { conn =>
      data
        .evalScan(pos) { (acc, chunk) =>
          insertChunk(id, lom, oid, chunk)(acc).inTx.run(conn)
        }
        .compile
        .lastOrError
    }

  def insertChunk(
      id: UploadId,
      lom: LargeObjectManager,
      oid: Long,
      chunk: Chunk[Byte]
  )(pos: ByteSize): DbTask[F, ByteSize] =
    (for
      obj <- DbTask.openLoWriteR(lom, oid)
      _ <- DbTask.delay { _ =>
        val bs = chunk.toArraySlice
        obj.seek64(pos.toBytes, LargeObject.SEEK_SET)
        obj.write(bs.values, bs.offset, bs.size)
      }.resource
      nextOffset = pos + ByteSize.bytes(chunk.size)
      _ <- fileTable.updateOffset(id, nextOffset).resource
    yield nextOffset).evaluated

  def findConcatFile(
      id: UploadId,
      chunkSize: ByteSize,
      makeConn: ConnectionResource[F],
      makeUrl: UploadId => Url
  ) =
    (for
      (state, oids) <- fileTable.findConcat(id, makeUrl).mapF(OptionT.apply)
      data = Stream
        .resource(makeConn)
        .flatMap(conn =>
          Stream
            .emits(oids.toVector)
            .covary[F]
            .flatMap(oid => loadFile(oid, ByteRange.All, chunkSize).run(conn))
        )
      res = FileResult(state, data, true, None, None)
    yield res).mapF(_.value)

  def findFile(
      id: UploadId,
      chunkSize: ByteSize,
      makeConn: ConnectionResource[F],
      range: ByteRange = ByteRange.All
  ): DbTask[F, Option[FileResult[F]]] =
    (for
      (state, oidOpt) <- fileTable.find(id).mapF(OptionT.apply)
      data = oidOpt match
        case Some(oid) =>
          Stream
            .resource(makeConn)
            .flatMap(loadFile(oid, range, chunkSize).run)
        case None => Stream.empty

      res = FileResult(state, data, oidOpt.isDefined, None, None)
    yield res).mapF(_.value)

  def find(
      id: UploadId,
      chunkSize: ByteSize,
      makeConn: ConnectionResource[F],
      makeUrl: UploadId => Url
  ) =
    findFile(id, chunkSize, makeConn)
      .flatMap {
        case r @ Some(_) => DbTask.pure(r)
        case None        => findConcatFile(id, chunkSize, makeConn, makeUrl)
      }

  def loadFile(oid: Long, range: ByteRange, chunkSize: ByteSize): DbTaskS[F, Byte] =
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
              chunkSize.toBytes.toInt
            )
          )
          .repeat
          .takeThrough(_.size == chunkSize.toBytes)
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

  def concatFiles(req: ConcatRequest) =
    val entries =
      req.ids.toList
        .traverse(id =>
          fileTable.find(id).map {
            case Some(state, Some(_)) if state.isPartial => Right(state)
            case _                                       => Left(id)
          }
        )
        .map(_.partitionMap(identity))
    entries.flatMap {
      case (Nil, parts) =>
        for
          id <- DbTask.liftF(UploadId.randomULID[F])
          _ <- fileTable.insertConcat(id, req.meta)
          _ <- fileTable.insertConcatParts(
            id,
            NonEmptyList.fromListUnsafe(parts.map(_.id))
          )
        yield ConcatResult.Success(id)

      case (missing, _) =>
        DbTask.pure(ConcatResult.PartsNotFound(NonEmptyList.fromListUnsafe(missing)))
    }

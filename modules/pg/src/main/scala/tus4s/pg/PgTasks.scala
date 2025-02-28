package tus4s.pg

import cats.data.NonEmptyList
import cats.data.OptionT
import cats.effect.*
import cats.syntax.all.*
import fs2.Chunk
import fs2.Stream
import fs2.hashing.Hash

import org.postgresql.largeobject.LargeObject
import org.postgresql.largeobject.LargeObjectManager
import tus4s.core.HashSupport
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

          (saved, hash) <-
            if (req.hasContent)
              insertFile(
                id,
                req.dataChunked(chunkSize, maxSize),
                maxSize,
                req.checksum.map(_.algorithm)
              )
            else DbTask.pure(ByteSize.zero -> Hash(Chunk.empty))

          res = req.checksum
            .map(_.hash)
            .filter(_ != hash)
            .map(_ => CreationResult.ChecksumMismatch)
            .orElse(
              Validation
                .validateCreate(req.copy(uploadLength = saved.some), maxSize)
            )
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
          receiveChunk0(
            id,
            oidOpt,
            state,
            req.checksum.map(_.algorithm),
            req.dataChunked(chunkSize, maxSize)
          ).map { case (size, hash) =>
            req.checksum
              .map(_.hash)
              .filter(_ != hash)
              .map(_ => ReceiveResult.ChecksumMismatch)
              .orElse {
                Validation
                  .validateReceive(state, req.copy(contentLength = size.some), maxSize)
              }
              .getOrElse(ReceiveResult.Success(size, None))
          }
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
      alg: Option[ChecksumAlgorithm],
      data: Stream[F, Chunk[Byte]]
  ): DbTask[F, (ByteSize, Hash)] =
    for
      lom <- DbTask.loManager[F]
      oid <- oidOpt.map(DbTask.pure).getOrElse {
        DbTask
          .createLargeObject(lom)
          .flatTap(oid => fileTable.updateOid(id, oid))
          .inTx
      }
      res <- insertSafe(id, data, oid, lom, alg, state.offset)
    yield res

  def insertFile(
      id: UploadId,
      data: Stream[F, Chunk[Byte]],
      maxSize: Option[ByteSize],
      alg: Option[ChecksumAlgorithm]
  ): DbTask[F, (ByteSize, Hash)] =
    for
      lom <- DbTask.loManager[F]
      oid <- DbTask
        .createLargeObject(lom)
        .flatTap(oid => fileTable.updateOid(id, oid))
        .inTx

      res <- insertSafe(id, data, oid, lom, alg, ByteSize.zero)
    yield res

  def insertSafe(
      id: UploadId,
      data: Stream[F, Chunk[Byte]],
      oid: Long,
      lom: LargeObjectManager,
      alg: Option[ChecksumAlgorithm],
      pos: ByteSize
  ): DbTask[F, (ByteSize, Hash)] =
    DbTask { conn =>
      Stream
        .resource(HashSupport.hasher(alg))
        .flatMap { hasher =>
          data
            .evalScan(pos) { (acc, chunk) =>
              hasher.update(chunk) >>
                insertChunk(id, lom, oid, chunk)(acc).inTx.run(conn)
            }
            .evalMap(pos => hasher.hash.map(h => (pos, h)))
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
      range: ByteRange,
      makeConn: ConnectionResource[F]
  ): DbTask[F, Option[FileResult[F]]] =
    (for
      concatFile <- fileTable
        .findConcat(id)
        .mapF(OptionT.apply)
      parts <- DbTask(_ => OptionT.pure(concatFile.applyRange(range)))
      (data, hasContent) = parts match {
        case None => (Stream.empty.covaryAll[F, Byte], false)
        case Some(nev) =>
          (
            Stream
              .resource(makeConn)
              .flatMap(conn =>
                Stream
                  .emits(nev.toVector)
                  .covary[F]
                  .flatMap { case (oid, r) => loadFile(oid, r, chunkSize).run(conn) }
              ),
            true
          )
      }
      res = FileResult(concatFile.state, data, hasContent, None, None)
    yield res).mapF(_.value)

  def findFile(
      id: UploadId,
      chunkSize: ByteSize,
      makeConn: ConnectionResource[F],
      range: ByteRange
  ): DbTask[F, Option[FileResult[F]]] =
    (for
      (state, oidOpt) <- fileTable.find(id).mapF(OptionT.apply)
      (data, hasContent) = oidOpt match
        case Some(oid) =>
          if (range.isEmpty) (Stream.empty, false)
          else
            (
              Stream
                .resource(makeConn)
                .flatMap(loadFile(oid, range, chunkSize).run),
              true
            )
        case None => (Stream.empty, false)

      res = FileResult(state, data, hasContent, None, None)
    yield res).mapF(_.value)

  def find(
      id: UploadId,
      range: ByteRange,
      chunkSize: ByteSize,
      makeConn: ConnectionResource[F],
      enableConcat: Boolean
  ) =
    findFile(id, chunkSize, makeConn, range)
      .flatMap {
        case r @ Some(_) => DbTask.pure(r)
        case None =>
          if (enableConcat) findConcatFile(id, chunkSize, range, makeConn)
          else DbTask.pure(None)
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
              range.fold(None, _.length.map(_.toBytes)),
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
          _ <- fileTable.insertConcat(id, req.uris, req.meta)
          _ <- fileTable.insertConcatParts(
            id,
            NonEmptyList.fromListUnsafe(parts.map(_.id))
          )
        yield ConcatResult.Success(id)

      case (missing, _) =>
        DbTask.pure(ConcatResult.PartsNotFound(NonEmptyList.fromListUnsafe(missing)))
    }

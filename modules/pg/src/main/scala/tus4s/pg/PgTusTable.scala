package tus4s.pg

import java.sql.Types

import cats.data.NonEmptyList
import cats.data.NonEmptyVector
import cats.data.OptionT
import cats.effect.*
import cats.syntax.all.*

import tus4s.core.data.ByteSize
import tus4s.core.data.ConcatType
import tus4s.core.data.MetadataMap
import tus4s.core.data.Url
import tus4s.core.data.{UploadId, UploadState}
import tus4s.pg.impl.DbTask
import tus4s.pg.impl.syntax.*

private[pg] class PgTusTable[F[_]: Sync](table: String):
  private val concatTable: String = s"${table}_concat"
  private val concatFilesTable: String = s"${table}_concat_parts"
  private val createStatement =
    s"""CREATE TABLE IF NOT EXISTS "$table" (
       |  id varchar not null primary key,
       |  file_offset bigint not null,
       |  file_length bigint,
       |  meta_data text not null,
       |  concat_type varchar,
       |  file_oid oid,
       |  inserted_at timestamptz default now()
       |)""".stripMargin

  private val createConcatStatement =
    s"""CREATE TABLE IF NOT EXISTS "$concatTable" (
       |  id varchar not null primary key,
       |  meta_data text not null,
       |  part_uris varchar not null,
       |  inserted_at timestamptz default now()
       |)""".stripMargin

  private val createConcatFilesStatement =
    s"""CREATE TABLE IF NOT EXISTS "$concatFilesTable" (
       |  id bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
       |  file_id varchar not null,
       |  part_id varchar not null,
       |  part_index int not null,
       |  CONSTRAINT file_id_fk FOREIGN KEY("file_id") REFERENCES "$concatTable"("id") ON DELETE CASCADE,
       |  CONSTRAINT part_id_fk FOREIGN KEY("part_id") REFERENCES "$table"("id") ON DELETE CASCADE
       |)""".stripMargin

  val create: DbTask[F, Unit] =
    DbTask.prepare(createStatement).update.void

  val createConcat: DbTask[F, Unit] =
    for
      _ <- DbTask.prepare(createConcatStatement).update.void
      _ <- DbTask.prepare(createConcatFilesStatement).update.void
    yield ()

  def insert(e: UploadState): DbTask[F, Int] =
    val sql =
      s"""INSERT INTO "$table" (id, file_offset, file_length, meta_data, concat_type) VALUES (?,?,?,?,?)"""
    DbTask.prepare(sql).updateWith { ps =>
      ps.setString(1, e.id.value)
      ps.setLong(2, e.offset.toBytes)
      e.length match
        case Some(len) => ps.setLong(3, len.toBytes)
        case None      => ps.setNull(3, Types.BIGINT)
      ps.setString(4, e.meta.encoded)
      e.concatType match
        case Some(t) => ps.setString(5, t.render)
        case None    => ps.setNull(5, Types.VARCHAR)
    }

  def insertConcat(id: UploadId, uris: NonEmptyList[Url], meta: MetadataMap) =
    val sql =
      s"""INSERT INTO "$concatTable" (id, part_uris, meta_data) VALUES (?, ?, ?)"""
    DbTask.prepare(sql).updateWith { ps =>
      ps.setString(1, id.value)
      ps.setString(2, ConcatType.Final(uris).render)
      ps.setString(3, meta.encoded)
    }

  def insertConcatParts(id: UploadId, parts: NonEmptyList[UploadId]) =
    val sql =
      s"""INSERT INTO "$concatFilesTable" (file_id, part_id, part_index) VALUES (?, ?, ?)"""
    val prepared = DbTask.prepare(sql)
    parts.toList.zipWithIndex.traverse { case (partId, idx) =>
      prepared.updateWith { ps =>
        ps.setString(1, id.value)
        ps.setString(2, partId.value)
        ps.setInt(3, idx)
      }
    }.void

  def find(id: UploadId): DbTask[F, Option[(UploadState, Option[Long])]] =
    val sql =
      s"""SELECT file_offset, file_length, meta_data, concat_type, file_oid FROM "$table" WHERE id = ?"""
    DbTask.prepare(sql).queryWith(_.setString(1, id.value)).readOption { rs =>
      val meta = MetadataMap
        .parseTus(rs.stringColumnRequire("meta_data"))
        .fold(sys.error, identity)
      val offset = ByteSize.bytes(rs.longColumnRequire("file_offset"))
      val len = rs.longColumn("file_length").map(ByteSize.bytes)
      val concatType =
        rs.stringColumn("concat_type").map(c => ConcatType.unsafeFromString(c))
      (UploadState(id, offset, len, meta, concatType), rs.longColumn("file_oid"))
    }

  def findConcat(id: UploadId): DbTask[F, Option[(UploadState, NonEmptyVector[Long])]] =
    val sql1 =
      s"""SELECT
         | f.meta_data,
         | (SELECT SUM(e.file_length) FROM "$concatFilesTable" p INNER JOIN "$table" e ON e.id = p.part_id WHERE p.file_id = ?) AS file_length,
         | f.part_uris as part_uris
         | FROM "$concatTable" f
         | WHERE f.id = ?
    """.stripMargin

    val sql2 = s"""SELECT e.file_oid as file_oid
                  | FROM "$table" e
                  | INNER JOIN "$concatFilesTable" p ON p.part_id = e.id
                  | INNER JOIN "$concatTable" f ON f.id = p.file_id
                  | WHERE f.id = ?
                  | ORDER BY p.part_index ASC
    """.stripMargin

    val envelope =
      DbTask
        .prepare(sql1)
        .queryWith { ps =>
          ps.setString(1, id.value)
          ps.setString(2, id.value)
        }
        .readOption { rs =>
          val meta = MetadataMap
            .parseTus(rs.stringColumnRequire("meta_data"))
            .fold(sys.error, identity)
          val len = ByteSize.bytes(rs.longColumnRequire("file_length"))
          val uris = rs.urlList("part_uris")
          (len, meta, uris)
        }

    val parts = DbTask.prepare(sql2).queryWith(_.setString(1, id.value)).readMany { rs =>
      rs.longColumnRequire("file_oid")
    }
    (for
      (len, meta, uris) <- envelope.mapF(OptionT.apply)
      oids <- parts.mapF(OptionT.liftF)
      oidsNel <- DbTask.liftF(OptionT.fromOption[F](NonEmptyVector.fromVector(oids)))
      state = UploadState(
        id,
        len,
        len.some,
        meta,
        ConcatType.Final(uris).some
      )
    yield (state, oidsNel)).mapF(_.value)

  def updateOffset(id: UploadId, offset: ByteSize) =
    val sql = s"""UPDATE "$table" SET file_offset = ? WHERE id = ?"""
    DbTask.prepare(sql).updateWith { ps =>
      ps.setLong(1, offset.toBytes)
      ps.setString(2, id.value)
    }

  def updateOid(id: UploadId, oid: Long) =
    val sql = s"""UPDATE "$table" SET file_oid = ? WHERE id = ?"""
    DbTask.prepare(sql).updateWith { ps =>
      ps.setLong(1, oid)
      ps.setString(2, id.value)
    }

  def updateLength(id: UploadId, length: ByteSize): DbTask[F, Int] =
    val sql =
      s"""UPDATE "$table" SET file_length = ? WHERE id = ? AND file_length is NULL"""
    DbTask.prepare(sql).updateWith { ps =>
      ps.setLong(1, length.toBytes)
      ps.setString(2, id.value)
    }

  private def findOid(id: UploadId): DbTask[F, Option[Long]] =
    val sql = s"""SELECT file_oid FROM "$table" WHERE id = ?"""
    DbTask
      .prepare(sql)
      .queryWith(ps => ps.setString(1, id.value))
      .mapF(_.use { rs =>
        Sync[F].blocking(if (rs.next()) Some(rs.getLong(1)) else None)
      })

  def delete(id: UploadId) =
    for
      oidOpt <- findOid(id)
      _ <- oidOpt match
        case None => DbTask.pure(())
        case Some(oid) =>
          DbTask.loManager[F].flatMap(lom => DbTask.lift(lom.delete(oid)))

      _ <- DbTask
        .prepare(s"""DELETE FROM "$table" WHERE id = ?""")
        .updateWith(ps => ps.setString(1, id.value))
    yield ()

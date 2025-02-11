package tus4s.pg

import java.sql.Types

import cats.effect.*
import cats.syntax.all.*

import tus4s.core.data.ByteSize
import tus4s.core.data.ConcatType
import tus4s.core.data.MetadataMap
import tus4s.core.data.{UploadId, UploadState}
import tus4s.pg.impl.DbTask
import tus4s.pg.impl.syntax.*

private[pg] class PgTusTable[F[_]: Sync](table: String):

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

  val create: DbTask[F, Unit] =
    DbTask.prepare(createStatement).update.void

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

  def find(id: UploadId): DbTask[F, Option[(UploadState, Option[Long])]] =
    val sql =
      s"""SELECT id, file_offset, file_length, meta_data, concat_type, file_oid FROM "$table" WHERE id = ?"""
    DbTask.prepare(sql).queryWith(_.setString(1, id.value)).readOption { rs =>
      val id = UploadId.unsafeFromString(rs.stringColumnRequire("id"))
      val meta = MetadataMap
        .parseTus(rs.stringColumnRequire("meta_data"))
        .fold(sys.error, identity)
      val offset = ByteSize.bytes(rs.longColumnRequire("file_offset"))
      val len = rs.longColumn("file_length").map(ByteSize.bytes)
      val concatType =
        rs.stringColumn("concat_type").map(c => ConcatType.unsafeFromString(c))
      (UploadState(id, offset, len, meta, concatType), rs.longColumn("file_oid"))
    }

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

  def findOid(id: UploadId): DbTask[F, Option[Long]] =
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

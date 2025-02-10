package tus4s.pg

import cats.effect.*

import tus4s.core.data.MetadataMap
import tus4s.core.data.{UploadId, UploadState}
import tus4s.pg.impl.DbTask

class PgTusTableTest extends DbTestBase:

  val tusTable = PgTusTable[IO]("tus_files")

  test("create, insert , find"):
    withRandomDB {
      for
        _ <- tusTable.create
        id <- DbTask.liftF(UploadId.randomULID[IO])
        s1 = UploadState(id = id, meta = MetadataMap.empty.withFilename("test.txt"))
        _ <- tusTable.insert(s1)
        s2 <- tusTable.find(id)
        _ = assertEquals(s1, s2.get._1)
      yield ()
    }

  test("create twice"):
    withRandomDB {
      for
        _ <- tusTable.create
        _ <- tusTable.create
        id <- DbTask.liftF(UploadId.randomULID[IO])
        _ <- tusTable.find(id)
      yield ()
    }

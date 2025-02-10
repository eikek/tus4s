package tus4s.pg

import cats.effect.*
import tus4s.core.data.{UploadId, UploadState}
import tus4s.pg.impl.DbTask
import tus4s.pg.impl.syntax.*
import tus4s.core.data.MetadataMap

class PgTusTableTest extends DbTestBase:

  val tusTable = PgTusTable[IO]("tus_files")

  test("create, insert , find"):
    val t = for
      _ <- tusTable.create
      id <- DbTask.liftF(UploadId.randomULID[IO])
      s1 = UploadState(id = id, meta = MetadataMap.empty.withFilename("test.txt"))
      _ <- tusTable.insert(s1)
      s2 <- tusTable.find(id)
//      _ = assertEquals(s1, s2.get)
      _ <- DbTask.liftF(IO.println(s2.get.meta.getString(MetadataMap.Key.fileName)))
    yield ()
    t.exec(randomDB)

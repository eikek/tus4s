package tus4s.pg

import cats.effect.*

import tus4s.core.data.MetadataMap
import tus4s.core.data.UploadId
import tus4s.core.data.UploadState
import tus4s.pg.impl.DbTask

class PgTasksTest extends DbTestBase:
  val table = PgTusTable[IO]("tus_files")
  val tasks = PgTasks[IO]("tus_files")

  test("find non existing file"):
    withRandomDB {
      for
        _ <- table.create
        r <- tasks.findFile(UploadId.unsafeFromString("one"), 8192)
        _ = assert(r.isEmpty)
      yield ()
    }

  test("find file, no data"):
    withRandomDB {
      for
        _ <- table.create
        id <- DbTask.liftF(UploadId.randomULID[IO])
        s1 = UploadState(id = id, meta = MetadataMap.empty.withFilename("test.txt"))
        _ <- table.insert(s1)
        r <- tasks.findFile(id, 8192)
        _ = assert(r.isDefined)
        fr = r.get
        _ = assertEquals(fr.state.id, id)
        _ = assert(!fr.hasContent)
        _ = assertEquals(fr.getFileName, Some("test.txt"))
      yield ()
    }

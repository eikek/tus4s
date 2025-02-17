package tus4s.pg

import cats.effect.*
import cats.syntax.all.*

import scodec.bits.ByteVector
import tus4s.core.data.*
import tus4s.pg.impl.DbTask
import wvlet.airframe.ulid.ULID

class PgTasksTest extends DbTestBase:
  val table = PgTusTable[IO]("tus_files")
  val tasks = PgTasks[IO]("tus_files")
  val chunkSize = ByteSize.kb(8)

  def uploadRequestFor(data: String) =
    UploadRequest
      .fromByteVector[IO](ByteVector.view(data.getBytes))
      .copy(meta = MetadataMap.empty.withFilename("hello.txt"))

  test("find non existing file"):
    withRandomDB {
      for
        _ <- table.create
        dbc <- sameConnection
        r <- tasks.findFile(UploadId.unsafeFromString("one"), chunkSize, dbc)
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
        dbc <- sameConnection
        r <- tasks.findFile(id, chunkSize, dbc)
        _ = assert(r.isDefined)
        fr = r.get
        _ = assertEquals(fr.state.id, id)
        _ = assert(!fr.hasContent)
        _ = assertEquals(fr.getFileName, Some("test.txt"))
      yield ()
    }

  test("create upload without data"):
    withRandomDB {
      for
        _ <- table.create
        req = uploadRequestFor("").copy(meta = MetadataMap.empty)
        dbc <- sameConnection
        result <- tasks.createUpload(req, chunkSize * 4, None)
        id = result match
          case CreationResult.Success(id, offset, expires) =>
            assertEquals(offset, ByteSize.zero)
            assertEquals(expires, None)
            assertEquals(ULID(id.value).toString, id.value)
            id
          case _ =>
            fail(s"Unexpected result: $result")

        frOpt <- tasks.findFile(id, chunkSize * 4, dbc)
        _ = assert(frOpt.isDefined)
        fr = frOpt.get
        _ = assert(!fr.hasContent)
        _ = assertEquals(fr.state, UploadState(id))
      yield ()
    }

  test("create upload with data"):
    withRandomDB {
      for
        _ <- table.create
        len = ByteSize.bytes(11)
        req = uploadRequestFor("hello world")
        result <- tasks.createUpload(req, chunkSize = ByteSize.kb(32), None)
        id = result match
          case CreationResult.Success(id, offset, expires) =>
            assertEquals(offset, len)
            assertEquals(ULID(id.value).toString, id.value)
            id
          case _ =>
            fail(s"Unexpected result: $result")
        dbc <- sameConnection
        frOpt <- tasks.findFile(id, chunkSize * 4, dbc)
        _ = assert(frOpt.isDefined)
        fr = frOpt.get
        _ = assert(fr.hasContent)
        _ = assertEquals(
          fr.state,
          UploadState(
            id,
            offset = len,
            length = len.some,
            meta = MetadataMap.empty.withFilename("hello.txt")
          )
        )
        recv <- DbTask.liftF(fr.data.through(fs2.text.utf8.decode).compile.string)
        _ = assertEquals(recv, "hello world")
      yield ()
    }

  test("create upload with data, store multiple chunks"):
    withRandomDB {
      for
        _ <- table.create
        dataStr = "hello world".repeat(20)
        len = ByteSize.bytes(11 * 20)
        req = uploadRequestFor(dataStr)
        result <- tasks.createUpload(req, chunkSize = ByteSize.bytes(11), None)
        id = result match
          case CreationResult.Success(id, offset, expires) =>
            assertEquals(offset, len)
            assertEquals(ULID(id.value).toString, id.value)
            id
          case _ =>
            fail(s"Unexpected result: $result")
        dbc <- sameConnection
        frOpt <- tasks.findFile(id, chunkSize * 4, dbc)
        _ = assert(frOpt.isDefined)
        fr = frOpt.get
        _ = assert(fr.hasContent)
        recv <- DbTask.liftF(fr.data.through(fs2.text.utf8.decode).compile.string)
        _ = assertEquals(recv, dataStr)
      yield ()
    }

  test("load file separate connections"):
    val dbName = newDbName.unsafeRunSync()
    val insert = for
      _ <- table.create
      req = uploadRequestFor("hello world".repeat(20))
      result <- tasks.createUpload(req, ByteSize.kb(32), None)
      id = result match
        case CreationResult.Success(id, _, _) => id
        case _                                => fail(s"Unexpecterd result: $result")
    yield id

    def findFile(id: UploadId) =
      tasks.findFile(id, chunkSize * 4, makeConnectionResource(dbName))

    for
      (_, drop) <- withDb(dbName).allocated

      id <- makeConnectionResource(dbName).use(insert.run)
      fileOpt <- makeConnectionResource(dbName).use(findFile(id).run)
      _ = assert(fileOpt.isDefined)
      file = fileOpt.get
      _ = assert(file.hasContent)

      recv <- file.data.through(fs2.text.utf8.decode).compile.string
      _ = assertEquals(recv, "hello world".repeat(20))
      _ <- drop
    yield ()

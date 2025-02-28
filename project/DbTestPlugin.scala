import sbt._
import sbt.Keys._
import scala.io.Source
import java.util.concurrent.atomic.AtomicReference
import scala.util.Try
import java.lang
import java.util.concurrent.TimeUnit

object DbTestPlugin extends AutoPlugin {

  object autoImport {
    val dbTests = taskKey[Unit]("Run the tests with a database turned on.")
  }

  import autoImport._

  // Enable on all sub projects by default, so the task dbTests runs
  // all tests in the root project
  override def trigger = PluginTrigger.AllRequirements

  private val process: AtomicReference[Process] = new AtomicReference(null)

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    Test / dbTests := Def
      .sequential(
        Def.task {
          val logger = streams.value.log
          val dataDir = (LocalRootProject / target).value / "pg_test_data"
          startPostgres(dataDir, logger)
          logger.info("Running tests…")
        },
        // https://www.scala-sbt.org/1.x/docs/Tasks.html#andFinally
        (Test / test)
          .all(ScopeFilter(inAggregates(ThisProject)))
          .andFinally(
            stopPostgres()
          )
      )
      .value,

    // Disable `dbTests` on all aggregates, otherwise it would
    // try starting/stopping the server
    dbTests / aggregate := false
  )

  def startPostgres(dataDir: File, logger: Logger): Unit = {
    logger.info(s"Starting PostgreSQL server in ${dataDir}…")
    val port = sys.env.getOrElse("POSTGRES_DB_PORT", "5432")
    val pb = new lang.ProcessBuilder("postgres-fg", dataDir.absolutePath, port)
    pb.redirectErrorStream(true)
    pb.redirectOutput(lang.ProcessBuilder.Redirect.INHERIT)
    val p = pb.start()

    waitForPostgres(logger)
    if (!p.isAlive()) {
      logger.error("PostgreSQL did not start up properly")
      sys.error("Could not start postgres")
    }

    process.getAndSet(p)
  }

  def waitForPostgres(logger: Logger, tries: Int = 0): Unit = {
    import scala.sys.process._

    if (tries >= 50) {
      logger.error("Cannot connect to postgres. Giving up")
      sys.error("Cannot connect to postgres. Giving up")
    }
    Thread.sleep(100)
    val rc = "pg_isready -d postgres -h localhost -p 5432".!
    if (rc == 0) ()
    else {
      logger.info("Waiting for postgres to come up…")
      waitForPostgres(logger, tries + 1)
    }
  }

  def stopPostgres(): Unit =
    Option(process.get()) match {
      case Some(p) =>
        p.destroy()
        Thread.sleep(100)
        if (p.isAlive()) {
          p.destroyForcibly()
        }

        p.waitFor(10, TimeUnit.SECONDS)

      case _ => ()
    }
}

import sbt._

object Dependencies {

  object V {
    val scala3 = "3.4.2"
    val http4s = "0.23.27"
    val munit = "1.0.0"
    val munitCatsEffect = "2.0.0"
    val scribe = "3.15.0"
  }

  val scribe = Seq(
    "com.outr" %% "scribe" % V.scribe,
    "com.outr" %% "scribe-slf4j2" % V.scribe,
    "com.outr" %% "scribe-cats" % V.scribe
  )

  val http4sCore = Seq(
    "org.http4s" %% "http4s-core" % V.http4s
  )

  val http4sDsl = Seq(
    "org.http4s" %% "http4s-dsl" % V.http4s
  )

  val http4sEmber = Seq(
    "org.http4s" %% "http4s-ember-server" % V.http4s
  )

  val http4sClient = Seq(
    "org.http4s" %% "http4s-client" % V.http4s
  )

  val munit = Seq(
    "org.scalameta" %% "munit" % V.munit,
    "org.scalameta" %% "munit-scalacheck" % V.munit,
    "org.typelevel" %% "munit-cats-effect" % V.munitCatsEffect
  )
}

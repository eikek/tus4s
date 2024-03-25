import sbt._

object Dependencies {

  object V {
    val scala3 = "3.3.3"
    val http4s = "0.23.26"
    val munit = "0.7.29"
    val munitCatsEffect = "1.0.7"
    val scribe = "3.13.0"
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

  val munit = Seq(
    "org.scalameta" %% "munit" % V.munit,
    "org.scalameta" %% "munit-scalacheck" % V.munit,
    "org.typelevel" %% "munit-cats-effect-3" % V.munitCatsEffect
  )
}

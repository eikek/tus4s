package http4stus.data

import cats.data.NonEmptyList
import cats.syntax.all.*

import http4stus.internal.StringUtil

enum Extension:
  case Creation(options: Set[CreationOptions])
  case Expiration
  case Checksum
  case Termination
  case Concatenation

  val names: NonEmptyList[String] = this match
    case Creation(opts) =>
      val more = opts.toList.map(_.name)
      NonEmptyList("creation", more)
    case _ => NonEmptyList.one(StringUtil.camelToKebab(productPrefix))

object Extension:
  private val all = Set(
    Creation(Set.empty),
    Expiration,
    Checksum,
    Termination,
    Concatenation
  )

  def findCreation(exts: Set[Extension]): Option[Creation] =
    ???

  def fromStrings(str: NonEmptyList[String]): Either[String, NonEmptyList[Extension]] = {
    def loop(
        in: List[String],
        skipped: List[String],
        out: Set[Extension]
    ): (List[String], Set[Extension]) =
      in match
        case h :: t =>
          val next = all.find(_.names.toList.contains(h)).toSet
          if (next.isEmpty) loop(t, h :: skipped, out)
          else loop(t, skipped, next ++ out)
        case Nil =>
          (skipped, out)

    val (remain, exts) = loop(str.toList, Nil, Set.empty)
    NonEmptyList.fromList(exts.toList) match
      case None                        => Left(s"No extensions found in: $str")
      case Some(nel) if remain.isEmpty => Right(nel)
      case Some(nel) =>
        nel.find(_ == Creation(Set.empty)) match
          case None => Left(s"Invalid extension options left: $remain")
          case Some(creation) =>
            val opts = remain.traverse(CreationOptions.fromString)
            opts
              .map(_.toSet)
              .map(Creation.apply)
              .map(c => NonEmptyList(c, nel.toList.filterNot(_ == creation)))
  }

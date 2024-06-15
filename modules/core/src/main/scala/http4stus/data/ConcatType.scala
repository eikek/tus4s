package http4stus.data

import cats.data.NonEmptyList
import cats.syntax.all.*

import http4stus.internal.StringUtil
import org.http4s.ParseFailure
import org.http4s.ParseResult
import org.http4s.Uri

enum ConcatType:
  case Final(partials: NonEmptyList[Uri])
  case Partial

  def isFinal: Boolean = this != Partial
  def isPartial: Boolean = this == Partial

object ConcatType:
  def fromString(s: String): ParseResult[ConcatType] =
    if ("partial".equalsIgnoreCase(s)) Right(Partial)
    else
      val (fn, uriStr) = s.span(_ != ';')
      if (!fn.equalsIgnoreCase("final"))
        Left(ParseFailure(s"Invalid concat type: $s", ""))
      else
        StringUtil.spaceList(uriStr.drop(1).trim) match
          case None =>
            Left(ParseFailure(s"No partial upload uris given with final concat: $s", ""))
          case Some(nel) =>
            nel.traverse(Uri.fromString).map(Final.apply)

  extension (self: Final)
    /** Extracts upload ids from the given uris. It uses the last segment of the uri path.
      * If a base-uri is specified, each url is checked whether both authorities match.
      */
    def resolveToId(baseUri: Option[Uri]): Either[String, NonEmptyList[UploadId]] =
      def toId(uri: Uri) =
        uri.path.segments.lastOption
          .map(_.decoded())
          .toList
          .traverse(UploadId.fromString)

      def isDescendent(uri: Uri): Boolean =
        baseUri.forall { base =>
          uri.authority == base.authority && uri.path.startsWith(base.path)
        }

      self.partials.toList
        .flatTraverse { uri =>
          if (!isDescendent(uri))
            Left(s"Partial url not valid for this endpoint: ${uri.renderString}")
          else toId(uri)
        }
        .flatMap(ids => NonEmptyList.fromList(ids).toRight(s"No upload ids available"))

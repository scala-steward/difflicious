package difflicious
import io.circe.{Encoder, Json}
import cats.data.Ior
import difflicious.DiffResult.{ListResult, SetResult, ValueResult, MapResult}
import difflicious.DifferOp.MatchBy
import difflicious.internal.EitherGetSyntax._
import difflicious.utils.TypeName
import izumi.reflect.Tag
import izumi.reflect.macrortti.LightTypeTag

import scala.collection.immutable.ListMap
import scala.collection.mutable

// FIXME: use LTag instead of Tag
// FIXME: don't use cats Ior
trait Differ[T] {
  type R <: DiffResult

  def diff(inputs: Ior[T, T]): R

  final def diff(actual: T, expected: T): R = diff(Ior.Both(actual, expected))

  /**
    * Create an new Differ instance where the given path will produce an ignored DiffResult
    */
  def updateWith(path: UpdatePath, op: DifferOp): Either[DifferUpdateError, Differ[T]]
}

sealed trait DifferOp

object DifferOp {
  final case class SetIgnored(isIgnored: Boolean) extends DifferOp
  sealed trait MatchBy[-A] extends DifferOp
  object MatchBy {
    case object Index extends MatchBy[Any]
    final case class ByFunc[A, B](func: A => B, aTag: Tag[A]) extends MatchBy[A]
  }

}

object UpdateDiffer {}

object Differ {
  trait ValueDiffer[T] extends Differ[T] {
    final override type R = DiffResult.ValueResult

    override def diff(inputs: Ior[T, T]): R
  }

  final class EqualsDiffer[T](isIgnored: Boolean, encoder: Encoder[T]) extends ValueDiffer[T] {
    override def diff(inputs: Ior[T, T]): DiffResult.ValueResult = inputs match {
      case Ior.Both(actual, expected) =>
        DiffResult.ValueResult
          .Both(
            actual = encoder.apply(actual),
            expected = encoder.apply(expected),
            isOk = isIgnored || actual == expected,
            isIgnored = isIgnored,
          )
      case Ior.Left(actual) =>
        DiffResult.ValueResult.ActualOnly(encoder.apply(actual), isIgnored = isIgnored)
      case Ior.Right(expected) =>
        DiffResult.ValueResult.ExpectedOnly(encoder.apply(expected), isIgnored = isIgnored)
    }

    override def updateWith(path: UpdatePath, op: DifferOp): Either[DifferUpdateError, EqualsDiffer[T]] = {
      val (step, nextPath) = path.next
      (step, op) match {
        case (Some(_), _) => Left(DifferUpdateError.PathTooLong(nextPath))
        case (None, DifferOp.SetIgnored(newIgnored)) =>
          Right(new EqualsDiffer[T](isIgnored = newIgnored, encoder = encoder))
        case (None, otherOp) => Left(DifferUpdateError.InvalidDifferOp(nextPath, otherOp, "EqualsDiffer"))
      }
    }
  }

  def useEquals[T](implicit encoder: Encoder[T]): ValueDiffer[T] =
    new EqualsDiffer[T](isIgnored = false, encoder = encoder)

  implicit val stringDiff: ValueDiffer[String] = useEquals[String]
  implicit val charDiff: ValueDiffer[Char] = useEquals[Char]
  implicit val booleanDiff: ValueDiffer[Boolean] = useEquals[Boolean]

  // FIXME: tuple instances
  class NumericDiffer[T](isIgnored: Boolean, numeric: Numeric[T], encoder: Encoder[T], tag: Tag[T])
      extends ValueDiffer[T] {
    override def diff(inputs: Ior[T, T]): DiffResult.ValueResult = inputs match {
      case Ior.Both(actual, expected) => {
        DiffResult.ValueResult.Both(
          encoder.apply(actual),
          encoder.apply(expected),
          isOk = isIgnored || numeric.equiv(actual, expected),
          isIgnored = isIgnored,
        )
      }
      case Ior.Left(actual)    => DiffResult.ValueResult.ActualOnly(encoder.apply(actual), isIgnored = isIgnored)
      case Ior.Right(expected) => DiffResult.ValueResult.ExpectedOnly(encoder.apply(expected), isIgnored = isIgnored)
    }

    override def updateWith(path: UpdatePath, op: DifferOp): Either[DifferUpdateError, NumericDiffer[T]] = {
      val (step, nextPath) = path.next
      (step, op) match {
        case (Some(_), _) => Left(DifferUpdateError.PathTooLong(nextPath))
        case (None, DifferOp.SetIgnored(newIgnored)) =>
          Right(new NumericDiffer[T](isIgnored = newIgnored, numeric = numeric, encoder = encoder, tag = tag))
        case (None, otherOp) =>
          Left(DifferUpdateError.InvalidDifferOp(nextPath, otherOp, "NumericDiffer"))
      }
    }
  }

  implicit def numericDiff[T](implicit numeric: Numeric[T], encoder: Encoder[T], tag: Tag[T]): ValueDiffer[T] =
    new NumericDiffer[T](isIgnored = false, numeric = numeric, encoder = encoder, tag = tag)

  final class RecordDiffer[T](
    fieldDiffers: ListMap[String, (T => Any, Differ[Any])],
    ignored: Boolean,
    tag: Tag[T],
  ) extends Differ[T] {
    override type R = DiffResult.RecordResult

    val typeName: TypeName = TypeName.fromTag(tag.tag)

    override def diff(inputs: Ior[T, T]): R = inputs match {
      case Ior.Both(actual, expected) => {
        val diffResults = fieldDiffers
          .map {
            case (fieldName, (getter, differ)) =>
              val diffResult = differ.diff(getter(actual), getter(expected))

              fieldName -> diffResult
          }
          .to(ListMap)
        DiffResult
          .RecordResult(
            typeName = typeName,
            fields = diffResults,
            matchType = MatchType.Both,
            isIgnored = ignored,
            isOk = ignored || diffResults.values.forall(_.isOk),
          )
      }
      case Ior.Left(value) => {
        val diffResults = fieldDiffers
          .map {
            case (fieldName, (getter, differ)) =>
              val diffResult = differ.diff(Ior.left(getter(value)))

              fieldName -> diffResult
          }
          .to(ListMap)
        DiffResult
          .RecordResult(
            typeName = typeName,
            fields = diffResults,
            matchType = MatchType.ActualOnly,
            isIgnored = ignored,
            isOk = diffResults.values.forall(_.isOk),
          )
      }
      case Ior.Right(expected) => {
        val diffResults = fieldDiffers
          .map {
            case (fieldName, (getter, differ)) =>
              val diffResult = differ.diff(Ior.Right(getter(expected)))

              fieldName -> diffResult
          }
          .to(ListMap)
        DiffResult
          .RecordResult(
            typeName = typeName,
            fields = diffResults,
            matchType = MatchType.ExpectedOnly,
            isIgnored = ignored,
            isOk = diffResults.values.forall(_.isOk),
          )
      }
    }

    override def updateWith(path: UpdatePath, op: DifferOp): Either[DifferUpdateError, RecordDiffer[T]] = {
      val (step, nextPath) = path.next
      step match {
        case Some(UpdateStep.RecordField(fieldName)) =>
          for {
            (getter, fieldDiffer) <- fieldDiffers
              .get(fieldName)
              .toRight(DifferUpdateError.NonExistentField(nextPath, fieldName))
            newFieldDiffer <- fieldDiffer.updateWith(nextPath, op)
          } yield new RecordDiffer[T](
            fieldDiffers = fieldDiffers.updated(fieldName, (getter, newFieldDiffer)),
            ignored = this.ignored,
            tag = tag,
          )
        case Some(_) => Left(DifferUpdateError.UnexpectedDifferType(nextPath, "record"))
        case None =>
          op match {
            case DifferOp.SetIgnored(newIgnored) =>
              Right(new RecordDiffer[T](fieldDiffers = fieldDiffers, ignored = newIgnored, tag = tag))
            case _: DifferOp.MatchBy[_] => Left(DifferUpdateError.InvalidDifferOp(nextPath, op, "record"))
          }

      }
    }

    def unsafeIgnoreField(fieldName: String): RecordDiffer[T] =
      updateWith(UpdatePath.of(UpdateStep.RecordField(fieldName)), DifferOp.SetIgnored(true)) match {
        case Left(_) =>
          throw new IllegalArgumentException(s"Cannot ignore field: field '$fieldName' is not part of record")
        case Right(differ) => differ
      }
  }

  implicit def mapDiffer[M[KK, VV] <: Map[KK, VV], A, B](
    implicit keyDiffer: ValueDiffer[A],
    valueDiffer: Differ[B],
    tag: Tag[M[A, B]],
  ): MapDiffer[M, A, B] =
    new MapDiffer(isIgnored = false, keyDiffer = keyDiffer, valueDiffer = valueDiffer, tag = tag)

  // FIXME: probably want some sort of ordering to maintain consistent order
  final class MapDiffer[M[KK, VV] <: Map[KK, VV], A, B](
    isIgnored: Boolean,
    keyDiffer: ValueDiffer[A],
    valueDiffer: Differ[B],
    tag: Tag[M[A, B]],
  ) extends Differ[M[A, B]] {
    override type R = MapResult

    val typeName: TypeName = TypeName.fromTag(tag.tag)

    override def diff(inputs: Ior[M[A, B], M[A, B]]): R = inputs match {
      // FIXME: consolidate all 3 cases
      case Ior.Both(actual, expected) =>
        val actualOnly = mutable.ArrayBuffer.empty[MapResult.Entry]
        val both = mutable.ArrayBuffer.empty[MapResult.Entry]
        val expectedOnly = mutable.ArrayBuffer.empty[MapResult.Entry]
        actual.foreach {
          case (k, actualV) =>
            expected.get(k) match {
              case Some(expectedV) =>
                both += MapResult.Entry(
                  jsonForKey(k, keyDiffer),
                  valueDiffer.diff(actualV, expectedV),
                )
              case None =>
                actualOnly += MapResult.Entry(
                  jsonForKey(k, keyDiffer),
                  valueDiffer.diff(Ior.Left(actualV)),
                )
            }
        }
        expected.foreach {
          case (k, expectedV) =>
            if (actual.contains(k)) {
              // Do nothing, already compared when iterating through actual
            } else {
              expectedOnly += MapResult.Entry(
                jsonForKey(k, keyDiffer),
                valueDiffer.diff(Ior.Right(expectedV)),
              )
            }
        }
        (actualOnly ++ both ++ expectedOnly).toVector
        MapResult(
          typeName = typeName,
          (actualOnly ++ both ++ expectedOnly).toVector,
          MatchType.Both,
          isIgnored = isIgnored,
          isOk = isIgnored || actualOnly.isEmpty && expectedOnly.isEmpty && both.forall(_.value.isOk),
        )
      case Ior.Left(actual) =>
        DiffResult.MapResult(
          typeName = typeName,
          entries = actual.map {
            case (k, v) =>
              MapResult.Entry(jsonForKey(k, keyDiffer), valueDiffer.diff(Ior.Left(v)))
          }.toVector,
          matchType = MatchType.ActualOnly,
          isIgnored = isIgnored,
          isOk = isIgnored,
        )
      case Ior.Right(expected) =>
        DiffResult.MapResult(
          typeName = typeName,
          entries = expected.map {
            case (k, v) =>
              MapResult.Entry(jsonForKey(k, keyDiffer), valueDiffer.diff(Ior.Right(v)))
          }.toVector,
          matchType = MatchType.ActualOnly,
          isIgnored = isIgnored,
          isOk = isIgnored,
        )
    }

    // FIXME:
    override def updateWith(path: UpdatePath, op: DifferOp): Either[DifferUpdateError, MapDiffer[M, A, B]] = {
      val (step, nextPath) = path.next
      step match {
        case Some(UpdateStep.DownTypeParam(idx)) =>
          if (idx == 1) { // the value
            valueDiffer.updateWith(nextPath, op).map { newValueDiffer =>
              new MapDiffer[M, A, B](
                isIgnored = isIgnored,
                keyDiffer = keyDiffer,
                valueDiffer = newValueDiffer,
                tag = tag,
              )
            }
          } else
            Left(DifferUpdateError.InvalidTypeParamIndex(path = nextPath, invalidIndex = idx, currentClassName = "Map")) // TODO: more accurate name?
        case Some(_: UpdateStep.DownSubtype | _: UpdateStep.RecordField) =>
          Left(DifferUpdateError.UnexpectedDifferType(nextPath, "Map"))
        case None =>
          op match {
            case DifferOp.SetIgnored(newIsIgnored) =>
              Right(
                new MapDiffer[M, A, B](
                  isIgnored = newIsIgnored,
                  keyDiffer = keyDiffer,
                  valueDiffer = valueDiffer,
                  tag = tag,
                ),
              )
            case _: MatchBy[_] =>
              Left(DifferUpdateError.InvalidDifferOp(nextPath, op, "Map"))
          }
      }
    }
  }

  implicit def seqDiffer[F[X] <: Seq[X], A](
    implicit itemDiffer: Differ[A],
    fullTag: Tag[F[A]],
    itemTag: Tag[A],
  ): SeqDiffer[F, A] =
    new SeqDiffer[F, A](
      isIgnored = false,
      matchBy = MatchBy.Index,
      itemDiffer = itemDiffer,
      fullTag = fullTag,
      itemTag = itemTag,
    )

  // FIXME: add matchBy
  final class SeqDiffer[F[X] <: Seq[X], A](
    isIgnored: Boolean,
    matchBy: MatchBy[A],
    itemDiffer: Differ[A],
    fullTag: Tag[F[A]],
    itemTag: Tag[A],
  ) extends Differ[F[A]] {
    override type R = ListResult

    val typeName: TypeName = TypeName.fromTag(fullTag.tag)

    override def diff(inputs: Ior[F[A], F[A]]): R = inputs match {
      case Ior.Both(actual, expected) => {
        matchBy match {
          case MatchBy.Index => {
            val diffResults = actual
              .map(Some(_))
              .zipAll(expected.map(Some(_)), None, None)
              .map {
                case (aOpt, eOpt) =>
                  val ior = Ior.fromOptions(aOpt, eOpt).get // guaranteed one of the Option is Some
                  itemDiffer.diff(ior)
              }
              .toVector

            ListResult(
              typeName = typeName,
              items = diffResults,
              matchType = MatchType.Both,
              isIgnored = isIgnored,
              isOk = isIgnored || diffResults.forall(_.isOk),
            )
          }
          case MatchBy.ByFunc(func, _) => {
            val (results, allIsOk) = diffMatchByFunc(actual, expected, func, itemDiffer)
            ListResult(
              typeName = typeName,
              items = results,
              matchType = MatchType.Both,
              isIgnored = isIgnored,
              isOk = isIgnored || allIsOk,
            )
          }
        }
      }
      case Ior.Left(actual) =>
        ListResult(
          typeName = typeName,
          items = actual.map { a =>
            itemDiffer.diff(Ior.Left(a))
          }.toVector,
          matchType = MatchType.ActualOnly,
          isIgnored = isIgnored,
          isOk = isIgnored,
        )
      case Ior.Right(expected) =>
        ListResult(
          typeName = typeName,
          items = expected.map { a =>
            itemDiffer.diff(Ior.Right(a))
          }.toVector,
          matchType = MatchType.ExpectedOnly,
          isIgnored = isIgnored,
          isOk = isIgnored,
        )
    }

    override def updateWith(path: UpdatePath, op: DifferOp): Either[DifferUpdateError, SeqDiffer[F, A]] = {
      val (step, nextPath) = path.next
      step match {
        case Some(UpdateStep.DownTypeParam(idx)) =>
          if (idx == 0) {
            itemDiffer.updateWith(nextPath, op).map { newItemDiffer =>
              new SeqDiffer[F, A](
                isIgnored = isIgnored,
                matchBy = matchBy,
                itemDiffer = newItemDiffer,
                fullTag = fullTag,
                itemTag = itemTag,
              )
            }
          } else Left(DifferUpdateError.InvalidTypeParamIndex(nextPath, idx, typeName.withTypeParamsLong))
        case Some(_: UpdateStep.DownSubtype | _: UpdateStep.RecordField) =>
          Left(DifferUpdateError.UnexpectedDifferType(nextPath, s"seq"))
        case None =>
          op match {
            case DifferOp.SetIgnored(newIsIgnored) =>
              Right(
                new SeqDiffer[F, A](
                  isIgnored = newIsIgnored,
                  matchBy = matchBy,
                  itemDiffer = itemDiffer,
                  fullTag = fullTag,
                  itemTag = itemTag,
                ),
              )
            case matchBy: DifferOp.MatchBy[_] =>
              matchBy match {
                case MatchBy.Index =>
                  Right(
                    new SeqDiffer[F, A](
                      isIgnored = isIgnored,
                      matchBy = MatchBy.Index,
                      itemDiffer = itemDiffer,
                      fullTag = fullTag,
                      itemTag = itemTag,
                    ),
                  )
                case m: MatchBy.ByFunc[_, _] =>
                  if (m.aTag.tag == itemTag.tag) {
                    Right(
                      new SeqDiffer[F, A](
                        isIgnored = isIgnored,
                        matchBy = m.asInstanceOf[DifferOp.MatchBy[A]],
                        itemDiffer = itemDiffer,
                        fullTag = fullTag,
                        itemTag = itemTag,
                      ),
                    )
                  } else {
                    Left(
                      DifferUpdateError
                        .MatchByTypeMismatch(nextPath, actualTag = m.aTag.tag, expectedTag = itemTag.tag),
                    )
                  }
              }
          }
      }
    }
  }

  implicit def setDiffer[F[X] <: Set[X], A](
    implicit itemDiffer: Differ[A],
    fullTag: Tag[F[A]],
    itemTag: Tag[A],
  ): SetDiffer[F, A] =
    new SetDiffer[F, A](isIgnored = false, itemDiffer, matchFunc = identity, fullTag = fullTag, itemTag = itemTag)

  // TODO: maybe find a way for stable ordering (i.e. only order on non-ignored fields)
  final class SetDiffer[F[X] <: Set[X], A](
    isIgnored: Boolean,
    itemDiffer: Differ[A],
    matchFunc: A => Any,
    fullTag: Tag[F[A]],
    itemTag: Tag[A],
  ) extends Differ[F[A]] {
    override type R = SetResult

    val typeName: TypeName = TypeName.fromTag(fullTag.tag)

    override def diff(inputs: Ior[F[A], F[A]]): R = inputs match {
      case Ior.Left(actual) =>
        SetResult(
          typeName = typeName,
          actual.toVector.map { a =>
            itemDiffer.diff(Ior.Left(a))
          },
          MatchType.ActualOnly,
          isIgnored = isIgnored,
          isOk = isIgnored,
        )
      case Ior.Right(expected) =>
        SetResult(
          typeName = typeName,
          items = expected.toVector.map { e =>
            itemDiffer.diff(Ior.Right(e))
          },
          matchType = MatchType.ExpectedOnly,
          isIgnored = isIgnored,
          isOk = isIgnored,
        )
      case Ior.Both(actual, expected) => {
        val (results, overallIsSame) = diffMatchByFunc(actual.toSeq, expected.toSeq, matchFunc, itemDiffer)
        SetResult(
          typeName = typeName,
          items = results,
          matchType = MatchType.Both,
          isIgnored = isIgnored,
          isOk = isIgnored || overallIsSame,
        )
      }
    }

    override def updateWith(path: UpdatePath, op: DifferOp): Either[DifferUpdateError, SetDiffer[F, A]] = {
      val (step, nextPath) = path.next
      step match {
        case Some(UpdateStep.DownTypeParam(idx)) =>
          if (idx == 0) {
            itemDiffer.updateWith(nextPath, op).map { updatedItemDiffer =>
              new SetDiffer[F, A](
                isIgnored = isIgnored,
                itemDiffer = updatedItemDiffer,
                matchFunc = matchFunc,
                fullTag = fullTag,
                itemTag = itemTag,
              )
            }
          } else Left(DifferUpdateError.InvalidTypeParamIndex(nextPath, idx, "Set"))
        case Some(_: UpdateStep.RecordField | _: UpdateStep.DownSubtype) =>
          Left(DifferUpdateError.UnexpectedDifferType(nextPath, "Set"))
        case None =>
          op match {
            case DifferOp.SetIgnored(newIsIgnored) =>
              Right(
                new SetDiffer[F, A](
                  isIgnored = newIsIgnored,
                  itemDiffer = itemDiffer,
                  matchFunc = matchFunc,
                  fullTag = fullTag,
                  itemTag = itemTag,
                ),
              )
            case m: MatchBy[_] =>
              m match {
                case MatchBy.Index => Left(DifferUpdateError.InvalidDifferOp(nextPath, m, "Set"))
                case m: MatchBy.ByFunc[_, _] =>
                  if (m.aTag.tag == itemTag.tag) {
                    Right(
                      new SetDiffer[F, A](
                        isIgnored = isIgnored,
                        itemDiffer = itemDiffer,
                        matchFunc = m.func.asInstanceOf[A => Any],
                        fullTag = fullTag,
                        itemTag = itemTag,
                      ),
                    )
                  } else {
                    Left(DifferUpdateError.MatchByTypeMismatch(nextPath, fullTag.tag, m.aTag.tag))
                  }
              }
          }

      }
    }

    def matchBy[B](func: A => B): SetDiffer[F, A] = {
      // Should always succeed, because method signature guarantees func takes an A
      updateWith(UpdatePath.current, MatchBy.ByFunc(func, itemTag)).unsafeGet
    }
  }

  // Given two lists of item, find "matching" items using te provided function
  // (where "matching" means ==). For example we might want to items by
  // person name.
  private def diffMatchByFunc[A](
    actual: Seq[A],
    expected: Seq[A],
    func: A => Any,
    itemDiffer: Differ[A],
  ): (Vector[DiffResult], Boolean) = {
    val matchedIndexes = mutable.BitSet.empty
    val results = mutable.ArrayBuffer.empty[DiffResult]
    val expWithIdx = expected.zipWithIndex
    var allIsOk = true
    actual.foreach { a =>
      val aMatchVal = func(a)
      val found = expWithIdx.find {
        case (e, idx) =>
          if (!matchedIndexes.contains(idx) && aMatchVal == func(e)) {
            val res = itemDiffer.diff(a, e)
            results += res
            matchedIndexes += idx
            allIsOk &= res.isOk
            true
          } else {
            false
          }
      }

      // FIXME: perhaps we need to prepend this to the front
      //  of all results for nicer result view?
      if (found.isEmpty) {
        results += itemDiffer.diff(Ior.Left(a))
        allIsOk = false
      }
    }

    expWithIdx.foreach {
      case (e, idx) =>
        if (!matchedIndexes.contains(idx)) {
          results += itemDiffer.diff(Ior.Right(e))
          allIsOk = false
        }
    }

    (results.toVector, allIsOk)
  }

  private def jsonForKey[T](k: T, keyDiffer: ValueDiffer[T]): Json = {
    keyDiffer.diff(Ior.Left(k)) match {
      case r: ValueResult.ActualOnly   => r.actual
      case r: ValueResult.Both         => r.actual
      case r: ValueResult.ExpectedOnly => r.expected
    }
  }

}

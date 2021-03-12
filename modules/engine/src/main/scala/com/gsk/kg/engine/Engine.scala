package com.gsk.kg.engine

import cats.Foldable
import cats.data.NonEmptyList
import cats.instances.all._
import cats.syntax.either._
import cats.syntax.applicative._
import org.apache.spark.sql.{Column, DataFrame, SQLContext}
import com.gsk.kg.sparqlparser._
import com.gsk.kg.sparqlparser.Expr.fixedpoint._
import higherkindness.droste._
import com.gsk.kg.sparqlparser.StringVal
import com.gsk.kg.engine.Multiset._
import com.gsk.kg.sparqlparser.StringVal._
import com.gsk.kg.sparqlparser.Expression
import org.apache.spark.sql.Encoder
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.types._
import java.{util => ju}

object Engine {

  def evaluateAlgebraM(implicit sc: SQLContext): AlgebraM[M, DAG, Multiset] =
    AlgebraM[M, DAG, Multiset] {
      case DAG.Describe(vars, r)     => notImplemented("Describe")
      case DAG.Ask(r)                => notImplemented("Ask")
      case DAG.Construct(bgp, r)     => evaluateConstruct(bgp, r)
      case DAG.Scan(graph, expr)     => notImplemented("Scan")
      case DAG.Project(variables, r) => r.select(variables: _*).pure[M]
      case DAG.Bind(variable, expression, r) =>
        evaluateBind(variable, expression, r)
      case DAG.Triple(s, p, o)         => evaluateTriple(s, p, o)
      case DAG.BGP(triples)            => Foldable[List].fold(triples).pure[M]
      case DAG.LeftJoin(l, r, filters) => evaluateLeftJoin(l, r, filters)
      case DAG.Union(l, r)             => l.union(r).pure[M]
      case DAG.Filter(funcs, expr)     => evaluateFilter(funcs, expr)
      case DAG.Join(l, r)              => notImplemented("Join")
      case DAG.Offset(offset, r)       => evaluateOffset(offset, r)
      case DAG.Limit(limit, r)         => evaluateLimit(limit, r)
      case DAG.Distinct(r)             => evaluateDistinct(r)
      case DAG.Noop(str)               => notImplemented("Noop")
    }

  def evaluate[T: Basis[DAG, *]](
      dataframe: DataFrame,
      dag: T
  )(implicit
      sc: SQLContext
  ): Result[DataFrame] = {
    val eval =
      scheme.cataM[M, DAG, T, Multiset](evaluateAlgebraM)

    eval(dag)
      .runA(dataframe)
      .map(_.dataframe)
  }

  private def evaluateDistinct(r: Multiset): M[Multiset] = {
    M.liftF(r.distinct)
  }

  private def evaluateLeftJoin(l: Multiset, r: Multiset, filters: List[Expression]): M[Multiset] = {
    NonEmptyList.fromList(filters).map { nelFilters =>
      evaluateFilter(nelFilters, r).flatMapF(l.leftJoin)
    }.getOrElse {
      M.liftF(l.leftJoin(r))
    }
  }

  private def evaluateFilter(funcs: NonEmptyList[Expression], expr: Multiset): M[Multiset] = {
    val compiledFuncs: NonEmptyList[DataFrame => Result[Column]] =
      funcs.map(ExpressionF.compile[Expression])

    M.liftF[Result, DataFrame, Multiset] {
      compiledFuncs.foldLeft(expr.asRight: Result[Multiset]) {
        case (eitherAcc, f) =>
          for {
            acc <- eitherAcc
            filterCol <- f(acc.dataframe)
            result <-
              expr
                .filter(filterCol)
                .map(r =>
                  expr.copy(dataframe = r.dataframe intersect acc.dataframe)
                )
          } yield result
      }
    }
  }

  private def evaluateOffset(offset: Long, r: Multiset): M[Multiset] =
    M.liftF(r.offset(offset))

  private def evaluateLimit(limit: Long, r: Multiset): M[Multiset] =
    M.liftF(r.limit(limit))

  /**
    * Evaluate a construct expression.
    *
    * Something we do in this that differs from the spec is that we
    * apply a default ordering to all solutions generated by the
    * [[bgp]], so that LIMIT and OFFSET can return meaningful results.
    */
  private def evaluateConstruct(bgp: Expr.BGP, r: Multiset)(implicit
      sc: SQLContext
  ): M[Multiset] = {
    import sc.implicits._

    val acc: DataFrame =
      List.empty[(String, String, String)].toDF("s", "p", "o")

    implicit val encoder: Encoder[Row] = RowEncoder(
      StructType(
        List(
          StructField("s", StringType),
          StructField("p", StringType),
          StructField("o", StringType)
        )
      )
    )

    // Extracting the triples to something that can be serialized in
    // Spark jobs
    val templateValues: List[List[(StringVal, Int)]] =
      bgp.triples
        .map(triple => List(triple.s -> 1, triple.p -> 2, triple.o -> 3))
        .toList

    val df = r.dataframe
      .flatMap { solution =>
        val extractBlanks: List[(StringVal, Int)] => List[StringVal] =
          triple => triple.filter(x => x._1.isBlank).map(_._1)

        val blankNodes: Map[String, String] =
          templateValues
            .flatMap(extractBlanks)
            .distinct
            .map(blankLabel => (blankLabel.s, ju.UUID.randomUUID().toString()))
            .toMap

        templateValues.map { triple =>
          val fields: List[Any] = triple
            .map({
              case (VARIABLE(s), pos) =>
                (solution.get(solution.fieldIndex(s)), pos)
              case (BLANK(x), pos) =>
                (blankNodes.get(x).get, pos)
              case (x, pos) =>
                (x.s, pos)
            })
            .sortBy(_._2)
            .map(_._1)

          Row.fromSeq(fields)
        }
      }
      .orderBy("s", "p")

    Multiset(
      Set.empty,
      df
    ).pure[M]
  }

  private def evaluateBind(
      bindTo: VARIABLE,
      bindFrom: Expression,
      r: Multiset
  ) = {
    val getColumn = ExpressionF.compile(bindFrom)

    M.liftF[Result, DataFrame, Multiset](
      getColumn(r.dataframe).map { col =>
        r.withColumn(bindTo, col)
      }
    )
  }

  private def evaluateTriple(
      s: StringVal,
      p: StringVal,
      o: StringVal
  )(implicit sc: SQLContext) = {
    import sc.implicits._
    M.get[Result, DataFrame].map { df: DataFrame =>
      val triple = Expr.Triple(s, p, o)
      val predicate = Predicate.fromTriple(triple)
      val current = applyPredicateToDataFrame(predicate, df)
      val variables = triple.getVariables
      val selected =
        current.select(variables.map(v => $"${v._2}".as(v._1.s)): _*)

      Multiset(
        variables.map(_._1.asInstanceOf[StringVal.VARIABLE]).toSet,
        selected
      )
    }
  }

  private def applyPredicateToDataFrame(
      predicate: Predicate,
      df: DataFrame
  ): DataFrame =
    predicate match {
      case Predicate.SPO(s, p, o) =>
        df.filter(df("s") === s && df("p") === p && df("o") === o)
      case Predicate.SP(s, p) =>
        df.filter(df("s") === s && df("p") === p)
      case Predicate.PO(p, o) =>
        df.filter(df("p") === p && df("o") === o)
      case Predicate.SO(s, o) =>
        df.filter(df("s") === s && df("o") === o)
      case Predicate.S(s) =>
        df.filter(df("s") === s)
      case Predicate.P(p) =>
        df.filter(df("p") === p)
      case Predicate.O(o) =>
        df.filter(df("o") === o)
      case Predicate.None =>
        df
    }

  private def notImplemented(constructor: String): M[Multiset] =
    M.liftF[Result, DataFrame, Multiset](
      EngineError.General(s"$constructor not implemented").asLeft[Multiset]
    )

}

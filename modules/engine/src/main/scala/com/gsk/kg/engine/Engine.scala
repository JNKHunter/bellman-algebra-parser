package com.gsk.kg.engine

import cats.Foldable
import cats.data.NonEmptyList
import cats.instances.all._
import cats.syntax.EitherSyntax
import cats.syntax.either._
import cats.syntax.applicative._
import org.apache.spark.sql.{Column, DataFrame, SQLContext}
import org.apache.spark.sql.functions._
import com.gsk.kg.engine._
import com.gsk.kg.sparqlparser._
import com.gsk.kg.sparqlparser.Expr.fixedpoint._
import higherkindness.droste._
import com.gsk.kg.sparqlparser.StringVal
import com.gsk.kg.engine.Multiset._
import com.gsk.kg.engine.Predicate.None
import com.gsk.kg.sparqlparser.Query
import com.gsk.kg.sparqlparser.Query.Construct
import com.gsk.kg.sparqlparser.BuildInFunc._
import com.gsk.kg.sparqlparser.StringVal._
import com.gsk.kg.sparqlparser.Expression

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
      case DAG.LeftJoin(l, r, filters) => notImplemented("LeftJoin")
      case DAG.Union(l, r)             => l.union(r).pure[M]
      case DAG.Filter(funcs, expr)     => evaluateFilter(funcs, expr)
      case DAG.Join(l, r)              => notImplemented("Join")
      case DAG.Offset(offset, r)       => evaluateOffset(offset, r)
      case DAG.Limit(limit, r)         => evaluateLimit(limit, r)
      case DAG.Distinct(r)             => notImplemented("Distinct")
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

  private def evaluateFilter(funcs: NonEmptyList[Expression], expr: Multiset): M[Multiset] = {

    val compiledFuncs: NonEmptyList[DataFrame => Result[Column]] = funcs.map(ExpressionF.compile[Expression])

    M.liftF[Result, DataFrame, Multiset] {
      compiledFuncs.foldLeft(expr.asRight: Result[Multiset]) { case (eitherAcc, f) =>
        for {
          acc <- eitherAcc
          filterCol <- f(acc.dataframe)
          result <- expr.filter(filterCol).map(r => expr.copy(dataframe = r.dataframe intersect acc.dataframe ))
        } yield result
      }
    }
  }

  private def evaluateOffset(offset: Long, r: Multiset): M[Multiset] =
    M.liftF(r.offset(offset))

  private def evaluateLimit(limit: Long, r: Multiset): M[Multiset] =
    M.liftF(r.limit(limit))

  private def evaluateConstruct[T](
      bgp: Expr.BGP,
      r: Multiset
  )(implicit sc: SQLContext, T: Basis[DAG, T]): M[Multiset] = {
    import sc.implicits._
    val acc = List.empty[(String, String, String)].toDF("s", "p", "o")

    Multiset(
      Set.empty,
      bgp.triples
        .map({ triple =>
          import org.apache.spark.sql.functions._

          val cols = (triple.getVariables ++ triple.getPredicates)

          cols
            .foldLeft(r.dataframe)({
              case (df, (sv, pos)) =>
                if (df.columns.contains(sv.s)) {
                  df.withColumnRenamed(sv.s, pos)
                } else {
                  df.withColumn(pos, lit(sv.s))
                }
            })
            .select("s", "p", "o")
        })
        .foldLeft(acc) { (acc, other) =>
          acc.union(other)
        }
        .dropDuplicates()
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

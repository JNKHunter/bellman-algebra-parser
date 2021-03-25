package com.gsk.kg.engine

import cats.kernel.Monoid
import cats.kernel.Semigroup
import cats.syntax.all._

import org.apache.spark.sql.Column
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._

import com.gsk.kg.engine.Multiset.filterGraph
import com.gsk.kg.sparqlparser.StringVal.GRAPH_VARIABLE
import com.gsk.kg.sparqlparser.StringVal.VARIABLE

/** A [[Multiset]], as expressed in SparQL terms.
  *
  * @see See [[https://www.w3.org/2001/sw/DataAccess/rq23/rq24-algebra.html]]
  * @param bindings the variables this multiset expose
  * @param dataframe the underlying data that the multiset contains
  */
final case class Multiset(
    bindings: Set[VARIABLE],
    dataframe: DataFrame
) {

  /** Join two multisets following SparQL semantics.  If two multisets
    * share some bindings, it performs an _inner join_ between them.
    * If they don't share any common binding, it performs a cross join
    * instead.
    *
    * =Spec=
    *
    * Defn: Join
    * Let Ω1 and Ω2 be multisets of mappings. We define:
    * Join(Ω1, Ω2) = { merge(μ1, μ2) | μ1 in Ω1and μ2 in Ω2, and μ1 and μ2 are compatible }
    * card[Join(Ω1, Ω2)](μ) = sum over μ in (Ω1 set-union Ω2), card[Ω1](μ1)*card[Ω2](μ2)
    *
    * @param other
    * @return the join result of both multisets
    */
  def join(other: Multiset): Multiset = {
    (this, other) match {
      case (l, r) if l.isEmpty => r
      case (l, r) if r.isEmpty => l
      case (l, r)
          if (filterGraph(l).bindings intersect filterGraph(
            r
          ).bindings).isEmpty =>
        Multiset.graphAgnostic(l, r) { (left, right) =>
          val df = left.dataframe.as("a").crossJoin(right.dataframe.as("b"))
          Multiset(left.bindings union right.bindings, df)
        }
      case (l, r) =>
        Multiset.graphAgnostic(l, r) { (left, right) =>
          val df = left.dataframe.join(
            right.dataframe,
            (left.bindings intersect right.bindings).toSeq.map(_.s),
            "inner"
          )
          Multiset(left.bindings union right.bindings, df)
        }
    }
  }

  /** A left join returns all values from the left relation and the matched values from the right relation,
    * or appends NULL if there is no match. It is also referred to as a left outer join.
    * @param other
    * @return
    */
  def leftJoin(other: Multiset): Result[Multiset] = Multiset
    .graphAgnostic(this, other) {
      case (l, r) if l.isEmpty => l
      case (l, r) if r.isEmpty => l
      case (Multiset(lBindings, lDF), Multiset(rBindings, rDF)) =>
        val df =
          lDF.join(rDF, (lBindings intersect rBindings).toSeq.map(_.s), "left")
        Multiset(lBindings union rBindings, df)
    }
    .asRight

  /** Perform a union between [[this]] and [[other]], as described in
    * SparQL Algebra doc.
    *
    * =Spec=
    *
    * Defn: Union
    * Let Ω1 and Ω2 be multisets of mappings. We define:
    * Union(Ω1, Ω2) = { μ | μ in Ω1 or μ in Ω2 }
    * card[Union(Ω1, Ω2)](μ) = card[Ω1](μ) + card[Ω2](μ)
    *
    * @param other
    * @return the Union of both multisets
    */
  def union(other: Multiset): Multiset = Multiset.graphAgnostic(this, other) {
    case (a, b) if a.isEmpty => b
    case (a, b) if b.isEmpty => a
    case (Multiset(aBindings, aDF), Multiset(bBindings, bDF))
        if aDF.columns == bDF.columns =>
      Multiset(
        aBindings.union(bBindings),
        aDF.union(bDF)
      )
    case (Multiset(aBindings, aDF), Multiset(bBindings, bDF)) =>
      val colsA     = aDF.columns.toSet
      val colsB     = bDF.columns.toSet
      val colsUnion = colsA.union(colsB)

      def genColumns(current: Set[String], total: Set[String]): Seq[Column] = {
        total.toList.sorted.map {
          case x if current.contains(x) => col(x)
          case x                        => lit(null).as(x) // scalastyle:ignore
        }
      }

      val bindingsUnion = aBindings union bBindings
      val selectionA    = aDF.select(genColumns(colsA, colsUnion): _*)
      val selectionB    = bDF.select(genColumns(colsB, colsUnion): _*)

      Multiset(
        bindingsUnion,
        selectionA.unionByName(selectionB)
      )
  }

  /** Return wether both the dataframe & bindings are empty
    *
    * @return
    */
  def isEmpty: Boolean = bindings.isEmpty && dataframe.isEmpty

  /** Get a new multiset with only the projected [[vars]].
    *
    * @param vars
    * @return
    */
  def select(vars: VARIABLE*): Multiset =
    Multiset(
      bindings.intersect(vars.toSet),
      dataframe.select(vars.map(v => dataframe(v.s)): _*)
    )

  /** Add a new column to the multiset, with the given binding
    *
    * @param binding
    * @param col
    * @param fn
    * @return
    */
  def withColumn(
      binding: VARIABLE,
      column: Column
  ): Multiset =
    Multiset(
      bindings + binding,
      dataframe
        .withColumn(binding.s, column)
    )

  /** A limited solutions sequence has at most given, fixed number of members.
    * The limit solution sequence S = (S0, S1, ..., Sn) is
    * limit(S, m) =
    * (S0, S1, ..., Sm-1) if n > m
    * (S0, S1, ..., Sn) if n <= m-1
    * @param limit
    * @return
    */
  def limit(limit: Long): Result[Multiset] = limit match {
    case l if l < 0 =>
      EngineError.UnexpectedNegative("Negative limit: $l").asLeft
    case l if l > Int.MaxValue.toLong =>
      EngineError
        .NumericTypesDoNotMatch(s"$l to big to be converted to an Int")
        .asLeft
    case l =>
      this.copy(dataframe = dataframe.limit(l.toInt)).asRight
  }

  /** An offset solution sequence with respect to another solution sequence S, is one which starts at a given index of S.
    * For solution sequence S = (S0, S1, ..., Sn), the offset solution sequence
    * offset(S, k), k >= 0 is
    * (Sk, Sk+1, ..., Sn) if n >= k
    * (), the empty sequence, if k > n
    * @param offset
    * @return
    */
  def offset(offset: Long): Result[Multiset] = if (offset < 0) {
    EngineError.UnexpectedNegative(s"Negative offset: $offset").asLeft
  } else {
    val rdd = dataframe.rdd.zipWithIndex
      .filter { case (_, idx) => idx >= offset }
      .map(_._1)
    val df =
      dataframe.sqlContext.sparkSession.createDataFrame(rdd, dataframe.schema)
    this.copy(dataframe = df).asRight
  }

  /** Filter restrict the set of solutions according to a given expression.
    * @param col
    * @return
    */
  def filter(col: Column): Result[Multiset] = {
    val filtered = dataframe.filter(col)
    this.copy(dataframe = filtered).asRight
  }

  /** Eliminates duplicates from the dataframe that matches the same variable binding
    * @return
    */
  def distinct: Result[Multiset] = {
    this
      .copy(
        dataframe = this.dataframe.distinct()
      )
      .asRight
  }
}

object Multiset {

  private val filterGraph: Multiset => Multiset = { m =>
    if (m.isEmpty) {
      m
    } else {
      m.copy(
        bindings = m.bindings.filter(_.s != GRAPH_VARIABLE.s),
        dataframe = m.dataframe.drop(GRAPH_VARIABLE.s)
      )
    }
  }

  private val addDefaultGraph: Multiset => Multiset = { m =>
    if (m.isEmpty) {
      m
    } else {
      m.copy(
        bindings = m.bindings + VARIABLE(GRAPH_VARIABLE.s),
        dataframe = m.dataframe.withColumn(GRAPH_VARIABLE.s, lit(""))
      )
    }
  }

  /** This methods is a utility to perform operations like [[union]], [[join]], [[leftJoin]] on Multisets without
    * taking into account graph bindings and graph columns on dataframes by:
    * removing from bindings and dataframe -> operate -> adding binding and column to final dataframe as default graph
    * @param right
    * @param left
    * @param f
    * @return
    */
  private def graphAgnostic(right: Multiset, left: Multiset)(
      f: (Multiset, Multiset) => Multiset
  ): Multiset =
    addDefaultGraph(f(filterGraph(right), filterGraph(left)))

  lazy val empty: Multiset =
    Multiset(Set.empty, SparkSession.builder().getOrCreate().emptyDataFrame)

  implicit val semigroup: Semigroup[Multiset] = new Semigroup[Multiset] {
    def combine(x: Multiset, y: Multiset): Multiset = x.join(y)
  }

  implicit def monoid(implicit sc: SQLContext): Monoid[Multiset] =
    new Monoid[Multiset] {
      def combine(x: Multiset, y: Multiset): Multiset = x.join(y)
      def empty: Multiset                             = Multiset(Set.empty, sc.emptyDataFrame)
    }

}

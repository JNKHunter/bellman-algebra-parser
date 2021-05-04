package com.gsk.kg.engine
package optimizer

import cats.implicits._

import higherkindness.droste.Basis

import com.gsk.kg.Graphs
import cats.arrow.Arrow

object Optimizer {

  def optimize[T: Basis[DAG, *]]: Phase[(T, Graphs), T] =
    GraphsPushdown.phase[T] >>>
      JoinBGPs.phase[T] >>>
      CompactBGPs.phase[T] >>>
      RemoveNestedProject.phase[T] >>>
      Arrow[Phase].lift(SubqueryPushdown[T])

}

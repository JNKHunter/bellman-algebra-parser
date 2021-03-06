package com.gsk.kg.engine
package optimizer

import cats.implicits._

import higherkindness.droste.Basis

import com.gsk.kg.engine.DAG._
import com.gsk.kg.engine.data.ToTree._

/** This optimization removes nested [[Project]] from the [[DAG]] when
  * they are consecutive and bind the same variables.
  *
  * In an initial construction of the [[DAG]], a query like this:
  *
  * {{{
  * val query = sparql"""
  *   PREFIX dm: <http://gsk-kg.rdip.gsk.com/dm/1.0/>
  *
  *   SELECT ?d
  *   WHERE {
  *     ?d a dm:Document .
  *     ?d dm:source "potato"
  *   }
  *   """
  * }}}
  *
  * Will be transformed to a [[DAG]] like follows:
  *
  * Project
  * |
  * +- List(VARIABLE(?d))
  * |
  * `- Project
  *    |
  *    +- List(VARIABLE(?d))
  *    |
  *    `- BGP
  *       |
  *       `- ChunkedList.Node
  *          |
  *          +- NonEmptyChain
  *          |  |
  *          |  `- Triple
  *          |     |
  *          |     +- ?d
  *          |     |
  *          |     +- http://gsk-kg.rdip.gsk.com/dm/1.0/source
  *          |     |
  *          |     `- potato
  *          |
  *          `- NonEmptyChain
  *             |
  *             `- Triple
  *                |
  *                +- ?d
  *                |
  *                +- http://www.w3.org/1999/02/22-rdf-syntax-ns#type
  *                |
  *                `- http://gsk-kg.rdip.gsk.com/dm/1.0/Document
  *
  * After this optimization pass, though, we convert it to a more
  * compact step like this (notice the [[DAG.Project]] deduplication):
  *
  * Project
  * |
  * +- List(VARIABLE(?d))
  * |
  * `- BGP
  *    |
  *    `- ChunkedList.Node
  *       |
  *       +- NonEmptyChain
  *       |  |
  *       |  `- Triple
  *       |     |
  *       |     +- ?d
  *       |     |
  *       |     +- http://gsk-kg.rdip.gsk.com/dm/1.0/source
  *       |     |
  *       |     `- potato
  *       |
  *       `- NonEmptyChain
  *          |
  *          `- Triple
  *             |
  *             +- ?d
  *             |
  *             +- http://www.w3.org/1999/02/22-rdf-syntax-ns#type
  *             |
  *             `- http://gsk-kg.rdip.gsk.com/dm/1.0/Document
  */
object RemoveNestedProject {

  def apply[T](implicit T: Basis[DAG, T]): T => T = { t =>
    T.coalgebra(t).rewrite { case p @ Project(vars1, r) =>
      T.coalgebra(r) match {
        case Project(vars2, r2) if vars1 == vars2 =>
          Project(vars2, r2)
        case _ => p
      }
    }
  }

  def phase[T](implicit T: Basis[DAG, T]): Phase[T, T] = Phase { t =>
    val result = apply(T)(t)
    (result != t)
      .pure[M]
      .ifM(
        Log.debug(
          "Optimizer(RemoveNestedProject)",
          s"resulting query: ${result.toTree.drawTree}"
        ),
        ().pure[M]
      ) *>
      result.pure[M]
  }
}

package com.gsk.kg.sparqlparser

sealed trait StringLike extends Expression

sealed trait StringFunc extends StringLike
sealed trait StringVal extends StringLike {
  val s: String
  def isVariable: Boolean = this match {
    case StringVal.STRING(s) => false
    case StringVal.NUM(s) => false
    case StringVal.VARIABLE(s) => true
    case StringVal.URIVAL(s) => false
    case StringVal.BLANK(s) => false
  }
}

object StringFunc {
  final case class URI(s:StringLike) extends StringFunc
  final case class CONCAT(appendTo:StringLike, append:StringLike) extends StringFunc
  final case class STR(s:StringLike) extends StringFunc
  final case class STRAFTER(s:StringLike, f:StringLike) extends StringFunc
  final case class ISBLANK(s: StringLike) extends StringFunc
  final case class REPLACE(st: StringLike, pattern: StringLike, by: StringLike) extends StringFunc
}

object StringVal {
  final case class STRING(s:String) extends StringVal
  final case class NUM(s:String) extends StringVal
  final case class VARIABLE(s:String) extends StringVal
  final case class URIVAL(s:String) extends StringVal
  final case class BLANK(s:String) extends StringVal
}

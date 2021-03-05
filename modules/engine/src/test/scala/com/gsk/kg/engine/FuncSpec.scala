package com.gsk.kg.engine

import org.scalatest.matchers.should.Matchers
import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.Row
import org.scalatest.wordspec.AnyWordSpec

class FuncSpec extends AnyWordSpec with Matchers with DataFrameSuiteBase {

  override implicit def reuseContextIfPossible: Boolean = true

  override implicit def enableHiveSupport: Boolean = false

  "Func.isBlank" should {

    "return whether a node is a blank node or not" in {
      import sqlContext.implicits._

      val df = List(
        "_:a",
        "a:a",
        "_:1",
        "1:1",
        "foaf:name",
        "_:name"
      ).toDF("text")

      val result = df.select(Func.isBlank(df("text"))).collect

      result shouldEqual Array(
        Row(true),
        Row(false),
        Row(true),
        Row(false),
        Row(false),
        Row(true)
      )
    }
  }

  "Func.replace" should {

    "replace when pattern occurs" in {
      import sqlContext.implicits._

      val df = List(
        "abcd",
        "abaB",
        "bbBB",
        "aaaa"
      ).toDF("text")

      val result = df.select(Func.replace(df("text"), "b", "Z"))
        .collect

      result shouldEqual Array(
        Row("aZcd"),
        Row("aZaB"),
        Row("ZZBB"),
        Row("aaaa")
      )
    }

    "replace(abracadabra, bra, *) returns a*cada*" in {
      import sqlContext.implicits._

      val df = List("abracadabra").toDF("text")

      val result = df.select(Func.replace(df("text"), "bra", "*"))
        .collect

      result shouldEqual Array(
        Row("a*cada*")
      )
    }

    "replace(abracadabra, a.*a, *) returns *" in {
      import sqlContext.implicits._

      val df = List("abracadabra").toDF("text")

      val result = df.select(Func.replace(df("text"), "a.*a", "*"))
        .collect

      result shouldEqual Array(
        Row("*")
      )
    }

    "replace(abracadabra, a.*?a, *) returns *c*bra" in {
      import sqlContext.implicits._

      val df = List("abracadabra").toDF("text")

      val result = df.select(Func.replace(df("text"), "a.*?a", "*"))
        .collect

      result shouldEqual Array(
        Row("*c*bra")
      )
    }

    "replace(abracadabra, a, \"\") returns brcdbr" in {
      import sqlContext.implicits._

      val df = List("abracadabra").toDF("text")

      val result = df.select(Func.replace(df("text"), "a", ""))
        .collect

      result shouldEqual Array(
        Row("brcdbr")
      )
    }

    "replace(abracadabra, a(.), a$1$1) returns abbraccaddabbra" in {
      import sqlContext.implicits._

      val df = List("abracadabra").toDF("text")

      val result = df.select(Func.replace(df("text"), "a(.)", "a$1$1"))
        .collect

      result shouldEqual Array(
        Row("abbraccaddabbra")
      )
    }

    "replace(abracadabra, .*?, $1) raises an error, because the pattern matches the zero-length string" in {
      import sqlContext.implicits._

      val df = List(
        "abracadabra"
      ).toDF("text")

      val caught = intercept[IndexOutOfBoundsException] {
        df.select(Func.replace(df("text"), ".*?", "$1"))
          .collect
      }

      caught.getMessage shouldEqual "No group 1"
    }

    "replace(AAAA, A+, b) returns b" in {
      import sqlContext.implicits._

      val df = List("AAAA").toDF("text")

      val result = df.select(Func.replace(df("text"), "A+", "b"))
        .collect

      result shouldEqual Array(
        Row("b")
      )
    }

    "replace(AAAA, A+?, b) returns bbbb" in {
      import sqlContext.implicits._

      val df = List(
        "AAAA"
      ).toDF("text")

      val result = df.select(Func.replace(df("text"), "A+?", "b"))
        .collect

      result shouldEqual Array(
        Row("bbbb")
      )
    }

    "replace(darted, ^(.*?)d(.*)$, $1c$2) returns carted. (The first d is replaced.)" in {
      import sqlContext.implicits._

      val df = List(
        "darted"
      ).toDF("text")

      val result = df.select(Func.replace(df("text"), "^(.*?)d(.*)$", "$1c$2"))
        .collect

      result shouldEqual Array(
        Row("carted")
      )
    }
  }

  "Func.strafter" should {

    "find the correct string if it exists" in {
      import sqlContext.implicits._

      val df = List(
        "hello#potato",
        "goodbye#tomato"
      ).toDF("text")

      df.select(Func.strafter(df("text"), "#").as("result"))
        .collect shouldEqual Array(
        Row("potato"),
        Row("tomato")
      )
    }

    "return empty strings otherwise" in {
      import sqlContext.implicits._

      val df = List(
        "hello potato",
        "goodbye tomato"
      ).toDF("text")

      df.select(Func.strafter(df("text"), "#").as("result"))
        .collect shouldEqual Array(
        Row(""),
        Row("")
      )
    }
  }

  "Func.iri" should {

    "do nothing for IRIs" in {
      import sqlContext.implicits._

      val df = List(
        "http://google.com",
        "http://other.com"
      ).toDF("text")

      df.select(Func.iri(df("text")).as("result")).collect shouldEqual Array(
        Row("http://google.com"),
        Row("http://other.com")
      )
    }
  }

  "Func.concat" should {

    "concatenate two string columns" in {
      import sqlContext.implicits._

      val df = List(
        ("Hello", " Dolly"),
        ("Here's a song", " Dolly")
      ).toDF("a", "b")

      df.select(Func.concat(df("a"), df("b")).as("verses"))
        .collect shouldEqual Array(
        Row("Hello Dolly"),
        Row("Here's a song Dolly")
      )
    }

    "concatenate a column with a literal string" in {
      import sqlContext.implicits._

      val df = List(
        ("Hello", " Dolly"),
        ("Here's a song", " Dolly")
      ).toDF("a", "b")

      df.select(Func.concat(df("a"), " world!").as("sentences"))
        .collect shouldEqual Array(
        Row("Hello world!"),
        Row("Here's a song world!")
      )
    }

    "concatenate a literal string with a column" in {
      import sqlContext.implicits._

      val df = List(
        ("Hello", " Dolly"),
        ("Here's a song", " Dolly")
      ).toDF("a", "b")

      df.select(Func.concat("Ciao", df("b")).as("verses"))
        .collect shouldEqual Array(
        Row("Ciao Dolly"),
        Row("Ciao Dolly")
      )
    }
  }
}

package com.wavesplatform.lang

import Context._
import cats.data.EitherT
import com.wavesplatform.lang.Evaluator.TrampolinedExecResult
import com.wavesplatform.lang.Terms.TYPE
import monix.eval.Coeval

case class Context(typeDefs: Map[String, CustomType], letDefs: Defs, functions: Map[String, CustomFunction])

object Context {

  type Defs = Map[String, (TYPE, Coeval[Any])]

  val empty = Context(Map.empty, Map.empty, Map.empty)

  case class CustomType(name: String, fields: List[(String, TYPE)])

  sealed trait CustomFunction {
    val name: String
    val args: List[(String, TYPE)]
    val resultType: TYPE
    def eval(args: List[Any]): TrampolinedExecResult[resultType.Underlying]
    val types: (List[TYPE], TYPE)
  }
  object CustomFunction {

    case class CustomFunctionImpl(name: String, resultType: TYPE, args: List[(String, TYPE)], ev: List[Any] => Either[String, Any])
        extends CustomFunction {
      override def eval(args: List[Any]): TrampolinedExecResult[resultType.Underlying] = {
        EitherT.fromEither[Coeval](ev(args).map(_.asInstanceOf[resultType.Underlying]))
      }
      override lazy val types: (List[TYPE], TYPE) = (args.map(_._2), resultType)
    }

    def apply(name: String, resultType: TYPE, args: List[(String, TYPE)])(ev: List[Any] => Either[String, resultType.Underlying]): CustomFunction =
      CustomFunctionImpl(name, resultType, args, ev)

  }

  sealed trait LazyVal {
    val tpe: TYPE
    val value: TrampolinedExecResult[tpe.Underlying]
  }

  object LazyVal {
    private case class LazyValImpl(tpe: TYPE, v: TrampolinedExecResult[Any]) extends LazyVal {
      override val value: TrampolinedExecResult[tpe.Underlying] = v.map(_.asInstanceOf[tpe.Underlying])
    }

    def apply(t: TYPE)(v: TrampolinedExecResult[t.Underlying]): LazyVal = LazyValImpl(t, v.map(_.asInstanceOf[Any]))
  }

  case class Obj(fields: Map[String, LazyVal])

}

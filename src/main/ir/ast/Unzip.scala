package ir.ast

import ir._
import ir.interpreter.Interpreter.ValueMap

/**
 * Unzip pattern.
 * Code for this pattern can be generated.
 *
 * The unzip pattern has the following high-level semantics:
 * `Unzip()( [ (x,,1,,, y,,1,,), ..., (x,,n,,, y,,n,,) ]) = ([x,,1,,, ..., x,,n,,], [y,,1,, , ..., y,,n,,] )`
 * The definitions for `n > 2` are accordingly.
 *
 * The unzip pattern has the following type:
 * `Unzip() : [a x b],,i,, -> ([a],,i,,, [b],,i,,)`
 * The definitions for `n > 2` are accordingly.
 */
case class Unzip() extends Pattern(arity = 1) {

  override def checkType(argType: Type,
                         setType: Boolean): Type = {
    argType match {
      case at @ ArrayType(tt: TupleType) =>
        TupleType(tt.elemsT.map(t => at.replacedElemT(t)):_* )

      case _ => throw new TypeException(argType, "ArrayType(TupleType, _)", this)
    }
  }


  override def eval(valueMap: ValueMap, args: Any*): Any = {
    assert(args.length == arity)
    args.head match {
      case vec: Vector[_] if vec.nonEmpty =>
        vec.head match {
          case _: (_, _) => vec.asInstanceOf[Vector[(_,_)]].unzip[Any, Any]
          case _: (_, _, _) => vec.asInstanceOf[Vector[(_,_,_)]].unzip3[Any, Any, Any]
        }

      case _ => throw new NotImplementedError()
    }
  }
}


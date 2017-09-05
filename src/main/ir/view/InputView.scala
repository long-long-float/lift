package ir.view

import lift.arithmetic.{ArithExpr, Var}
import ir._
import ir.ast._
import opencl.ir.pattern.{FilterSeq, MapSeqSlide, ReduceWhileSeq}

/**
 * A helper object for constructing views.
 *
 * Visits the expressions right to left and builds the views for all
 * sub-expressions that read to memory.
 */
object InputView {

  /**
   * Build input views for the expression.
   *
   * @param expr Expression to build views for
   */
  def apply(expr: Expr): Unit = {
    val itDimNum = ItSpaceDimCount(expr)
    BuildItVarInfo(expr, itDimNum)
    visitAndBuildViews(expr)
  }

  private def visitAndBuildViews(expr: Expr): View = {
    val result = expr match {
      case v: Value => if (v.view == NoView) View(v.t, v) else v.view
      case vp: VectorParam => vp.p.view
      case p: Param => p.view

      case ArrayFromValue(value, at) => ViewConstant(value, at)
      case ArrayFromGenerator(f, at) => ViewGenerator(f, at)
      case ArrayFromUserFunGenerator(f, at) => ViewGeneratorUserFun(f, at)
      case Array2DFromUserFunGenerator(f, at) => View2DGeneratorUserFun(f, at)
      case Array3DFromUserFunGenerator(f, at) => View3DGeneratorUserFun(f, at)

      case call: FunCall => buildViewFunCall(call)
    }
    expr.view = result
    result
  }

  private def getViewFromArgs(call: FunCall): View = {
    if (call.args.isEmpty) {
      assert(false)
      NoView
    } else if (call.args.length == 1) {
      visitAndBuildViews(call.args.head)
    } else {
      View.tuple(call.args.map((expr: Expr) => visitAndBuildViews(expr)):_*)
    }
  }

  private def buildViewFunCall(call: FunCall): View = {
    val argView = getViewFromArgs(call)

    call.f match {
      case m: AbstractMap => buildViewMap(m, call, argView)
      case f: FilterSeq => buildViewFilter(f, call, argView)
      case r: AbstractPartRed => buildViewReduce(r, call, argView)
      case sp: MapSeqSlide => buildViewMapSeqSlide(sp, call, argView)
      case s: AbstractSearch => buildViewSearch(s, call, argView)
      case l: Lambda => buildViewLambda(l, call, argView)
      case z: Zip => buildViewZip(call, argView)
      case uz: Unzip => buildViewUnzip(call, argView)
      case t: Tuple => buildViewTuple(argView)
      case Get(n) => buildViewGet(n, argView)
      case Split(n) => buildViewSplit(n, argView)
      case _: Join => buildViewJoin(call, argView)
      case uf: UserFun => buildViewUserFunDef(call)
      case uf: VectorizeUserFun => buildViewUserFunDef(call)
      case g: Gather => buildViewGather(g, call, argView)
      case i: Iterate => buildViewIterate(i, call, argView)
      case t: Transpose => buildViewTranspose(t, call, argView)
      case tw: TransposeW => buildViewTransposeW(tw, call, argView)
      case asVector(n) => buildViewAsVector(n, argView)
      case _: asScalar => buildViewAsScalar(argView)
      case f: Filter => buildViewFilter(call, argView)
      case g: Slide => buildViewGroup(g, call, argView)
      case h: Head => buildViewHead(call, argView)
      case h: Tail => buildViewTail(call, argView)
      case uaa: UnsafeArrayAccess => buildViewUnsafeArrayAccess(uaa, call, argView)
      case ca: CheckedArrayAccess => buildViewCheckedArrayAccess(ca, call, argView)
      case fp: FPattern => buildViewLambda(fp.f, call, argView)
      case Pad(left, right,boundary) => buildViewPad(left, right, boundary, argView)
      case ArrayAccess(i) => argView.access(i)
      case PrintType() | Scatter(_) | _: Tuple | Pad(_, _, _) => argView
      case dunno => throw new NotImplementedError(s"inputView.scala: $dunno")
    }
  }

  private def buildViewTuple(argView: View): View = {
    assert(argView.isInstanceOf[ViewTuple])
    // argView must already be a tuple
    argView
  }

  private def buildViewGet(n: Int, argView: View): View = {
    argView.get(n)
  }

  private def buildViewGroup(g: Slide, call: FunCall, argView: View): View = {
    argView.slide(g)
  }

  private def buildViewIterate(i: Iterate, call: FunCall, argView: View): View = {

    var firstSeenVar : Option[Var] = None
    i.f.params(0).view = View.visit(argView, pre = {(v:View) => {v match {
      case ViewMem(v, t) =>
        if (firstSeenVar.isEmpty)
          firstSeenVar = Some(v)
        else if (firstSeenVar.get != v)
          throw new NotImplementedError("Iterate can only work if the input received comes from a single memory view")
        ViewMem(i.vPtrIn,t)
      case _ => v
    }}})

    visitAndBuildViews(i.f.body)
    View.initialiseNewView(call.t, call.inputDepth, i.f.body.mem.variable)
  }


  private def buildViewMap(m: AbstractMap, call: FunCall, argView: View): View = {


    // pass down input view
    m.f.params(0).view = argView.access(m.loopVar)

    // traverse into call.f
    val innerView = visitAndBuildViews(m.f.body)

    m.f.body match {
      case innerCall: FunCall if innerCall.f.isInstanceOf[UserFun] =>
        // create fresh input view for following function
        View.initialiseNewView(call.t, call.inputDepth, call.mem.variable)
      case _ => // call.isAbstract and return input map view
        ViewMap(innerView, m.loopVar, call.t)
    }
  }
  
  private def buildViewFilter(f: FilterSeq, call: FunCall, argView: View): View = {
    // The inputs are the same for both the predicate and the copy function
    f.f.params.head.view = argView.access(f.loopRead)
    f.copyFun.params.head.view = argView.access(f.loopRead)
    
    visitAndBuildViews(f.f.body)
    val innerView = visitAndBuildViews(f.copyFun.body)
    f.copyFun.body match {
      case innerCall: FunCall if innerCall.f.isInstanceOf[UserFun] =>
        // create fresh input view for following function
        View.initialiseNewView(call.t, call.inputDepth, call.mem.variable)
      case _ => // call.isAbstract and return input map view
        ViewMap(innerView, f.loopRead, call.t)
    }
  }
  
  private def buildViewReduce(r: AbstractPartRed,
                              call: FunCall, argView: View): View = {
    // pass down input view
    r.f.params(0).view = argView.get(0)
    r.f.params(1).view = argView.get(1).access(r.loopVar)
    // traverse into call.f
    visitAndBuildViews(r.f.body)

    // if the reduction is a while reduction, visit and set views of the predicate
    r match {
      case rws: ReduceWhileSeq =>
        rws.p.params(0).view = argView.get(0)
        rws.p.params(1).view = argView.get(1).access(r.loopVar)
        visitAndBuildViews(rws.p.body)
      case _ =>
    }
    // create fresh input view for following function
    View.initialiseNewView(call.t, call.inputDepth, call.mem.variable)
  }

  private def buildViewMapSeqSlide(sp: MapSeqSlide,
                                    call: FunCall, argView: View): View = {

    sp.f.params(0).view = ViewMem(sp.windowVar, sp.f.params(0).t)

    // traverse into call.f
    val innerView = visitAndBuildViews(sp.f.body)

    sp.f.body match {
      case innerCall: FunCall if innerCall.f.isInstanceOf[UserFun] =>
        // create fresh input view for following function
        View.initialiseNewView(call.t, call.inputDepth, call.mem.variable)
      case _ => // call.isAbstract and return input map view
        ViewMap(innerView, sp.loopVar, call.t)
    }
  }

  private def buildViewSearch(s:AbstractSearch, call:FunCall, argView:View) : View = {
    // pass down input view
    s.f.params(0).view = argView.get(1).access(s.indexVar)
    // traverse into call.f
    visitAndBuildViews(s.f.body)
    // create fresh input view for following function
    View.initialiseNewView(call.t, call.inputDepth, call.mem.variable)
  }

  private def buildViewLambda(l: Lambda, call: FunCall, argView: View): View = {
    assert(call.args.nonEmpty)
    if (call.args.length == 1) {
      if (l.params.length != 1) throw new NumberOfArgumentsException
      l.params(0).view = argView
    } else {
      l.params.zipWithIndex.foreach({ case (p, i) => p.view = argView.get(i) })
    }
    visitAndBuildViews(l.body)
  }

  private def buildViewZip(call: FunCall, argView: View): View = {
    argView.zip()
  }

  private def buildViewUnzip(call: FunCall, argView: View): View = {
    argView.unzip()
  }

  private def buildViewFilter(call: FunCall, argView: View): View = {
    argView.get(0).filter(argView.get(1))
  }

  private def buildViewJoin(call: FunCall, argView: View): View = {
    val chunkSize = call.argsType match {
      case ArrayType(ArrayTypeWSWC(_, s,c)) if s==c => s
      case _ => throw new IllegalArgumentException("PANIC, expected 2D array, found " + call.argsType)
    }

    argView.join(chunkSize)
  }

  private def buildViewSplit(n: ArithExpr, argView: View): View = {
    argView.split(n)
  }

  private def buildViewAsVector(n: ArithExpr, argView: View): View = {
    argView.asVector(n)
  }

  private def buildViewAsScalar(argView: View): View = {
    argView.asScalar()
  }

  private def buildViewUserFunDef(call: FunCall): View = {
    View.initialiseNewView(call.t, call.inputDepth, call.mem.variable)
  }

  private def buildViewTranspose(t: Transpose, call: FunCall, argView: View): View = {
    call.t match {
      case ArrayTypeWS(ArrayTypeWS(typ, m), n) =>
        argView.
          join(n).
          reorder((i: ArithExpr) => { transpose(i, call.t) }).split(m)
      case NoType | ScalarType(_, _) | TupleType(_) | UndefType | VectorType(_, _) =>
        throw new TypeException(call.t, "Array", call.f)
    }
  }

  private def buildViewTransposeW(tw: TransposeW, call: FunCall, argView: View): View = {
    call.t match {
      case ArrayTypeWS(ArrayTypeWS(typ, m), n) =>
        argView.
          join(n).
          split(m)
      case NoType | ScalarType(_, _) | TupleType(_) | UndefType | VectorType(_, _) | ArrayType(_) =>
        throw new TypeException(call.t, "Array", call.f)
    }
  }

  private def buildViewGather(gather: Gather, call: FunCall, argView: View): View = {
    argView.reorder( (i:ArithExpr) => { gather.idx.f(i, call.t) } )
  }

  private def buildViewHead(head: FunCall, argView: View) : View = {
    ViewHead(argView.access(0), head.t)
  }

  private def buildViewTail(tail: FunCall, argView: View) : View = {
    ViewTail(argView, tail.t)
  }

  private def buildViewUnsafeArrayAccess(a: UnsafeArrayAccess, call: FunCall, argView: View) : View = {
   // visit the index
   visitAndBuildViews(a.index)
   View.initialiseNewView(call.t, call.inputDepth, call.mem.variable)
  }

  private def buildViewCheckedArrayAccess(a: CheckedArrayAccess, call: FunCall, argView: View) : View = {
    // visit the index
    visitAndBuildViews(a.index)
//    visitAndBuildViews(a.default)
    View.initialiseNewView(call.t, call.inputDepth, call.mem.variable)
  }

  private def buildViewPad(left: Int, right: Int, boundary: Pad.BoundaryFun, argView: View) : View = {
    argView.pad(left, right, boundary)
  }
}

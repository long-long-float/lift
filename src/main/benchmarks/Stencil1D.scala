package benchmarks

import apart.arithmetic.Var
import ir._
import ir.ast.Pad.BoundaryFun
import ir.ast._
import opencl.ir._
import opencl.ir.pattern._
import opencl.executor.Utils

class Stencil1D(override val f: Seq[(String, Array[Lambda])]) extends Benchmark("Stencil1D", Seq(1024 * 1024), f, 0.01f) {


  override def runScala(inputs: Any*): Array[Float] = {
    Stencil1D.runScala(inputs(0).asInstanceOf[Array[Float]])
  }

  override def generateInputs(): Seq[Any] = {
    val inputSizeN = inputSizes()(0)
    //val inputData = Array.tabulate(inputSizeM, inputSizeN)((r, c) => (((r * 3 + c * 2) % 10) + 1) * 0.1f)
    val inputData = Array.tabulate(inputSizeN)(x => util.Random.nextFloat())

    Seq(inputData, Stencil1D.weights)
  }

  override def globalSize: Array[Int] = {
    Array(inputSizes()(0),1,1)
  }
}

object Stencil1D{

  val scalaClamp = (idx: Int, length: Int) => {
    if(idx<0) 0 else if(idx>length-1) length-1 else idx
  }

  val scalaWrap = (idx: Int, length: Int) => {
    (idx % length + length) % length
  }

  val scalaMirror = (idx: Int, length: Int) => {
    val id = (if(idx < 0) -1-idx else idx) % (2*length)
    if(id >= length) length+length-id-1 else id
  }

  val size = 3
  val step = 1
  val left = 1
  val right = 1
  val scalaBoundary = scalaWrap
  val makePositive = UserFun("makePositive", "i", "{ return (i < 0) ? 0 : i;  }", Float, Float)
  val weights = Array(025f, 0.5f, 0.25f)


  def runScala(input: Array[Float]): Array[Float] = {
    Utils.scalaCompute1DStencil(input, size, step, left, right, weights, scalaBoundary)
  }

  def create1DStencilLambda(boundary: BoundaryFun): Lambda2 = {
    fun(
      ArrayType(Float, Var("N")),
      ArrayType(Float, weights.length),
      (input, weights) => {
        MapGlb(
          fun(neighbourhood => {
            toGlobal(MapSeqUnroll(makePositive)) o
              ReduceSeqUnroll(fun((acc, y) => {
                multAndSumUp.apply(acc, Get(y, 0), Get(y, 1))
              }), 0.0f) $
              Zip(weights, neighbourhood)
          })
        ) o Slide(size, step) o Pad(left, right, boundary) $ input
      }
    )
  }

  def createNaiveLocalMemory1DStencilLambda(boundary: BoundaryFun): Lambda2 = {
    fun(
      ArrayType(Float, Var("N")),
      ArrayType(Float, weights.length),
      (input, weights) => {
        MapWrg(MapLcl(
          fun(neighbourhood => {
            toGlobal(MapSeqUnroll(makePositive)) o
              ReduceSeqUnroll(fun((acc, y) => {
                multAndSumUp.apply(acc, Get(y, 0), Get(y, 1))
              }), 0.0f) $
              Zip(weights, toLocal(MapSeqUnroll(id)) $ neighbourhood)
          }))
        ) o Split(2) o Slide(size, step) o Pad(left, right, boundary) $ input
      }
    )
  }

  def apply() = new Stencil1D(
    Seq(
      ("3_POINT_1D_STENCIL_CLAMP", Array[Lambda](create1DStencilLambda(Pad.Boundary.Clamp))),
      ("3_POINT_1D_STENCIL_MIRROR_UNSAFE", Array[Lambda](create1DStencilLambda(Pad.Boundary.MirrorUnsafe))),
      ("3_POINT_1D_STENCIL_WRAP", Array[Lambda](create1DStencilLambda(Pad.Boundary.Wrap))),
      ("3_POINT_1D_STENCIL_MIRROR", Array[Lambda](create1DStencilLambda(Pad.Boundary.Mirror))),
      ("EXPERIMENTAL_LOCAL_MEM", Array[Lambda](createNaiveLocalMemory1DStencilLambda(Pad.Boundary.Wrap)))
    )
  )

  def main(args: Array[String]) = {
    Stencil1D().run(args)
  }
}

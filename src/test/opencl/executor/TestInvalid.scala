/**
 * Test cases for diagnostics and error recovery in the Executor.
 */

package opencl.executor

import arithmetic.Var
import ir._
import opencl.ir.{MapGlb, Float}
import org.junit._

object TestInvalid {
  @BeforeClass def before() {
    Executor.loadLibrary()
    println("Initialize the executor")
    Executor.init()
  }

  @AfterClass def after() {
    println("Shutdown the executor")
    Executor.shutdown()
  }
}

class TestInvalid {
  // Dummy user function
  val fct = UserFunDef("afunc", "array", " return array * 2.0f; ", Float, Float)
  // Dummy function
  val f = fun(ArrayType(Float, Var("N")), (in) => MapGlb(fun(a => fct(a))) $ in )
  val f2 = fun(ArrayType(Float, Var("N")), ArrayType(Float, Var("N")),
    (in1, in2) => MapGlb(fun(a => fct(a))) $ in1 )
  val f3 = fun(ArrayType(Float, Var("N")), ArrayType(Float, Var("N")), ArrayType(Float, Var("N")),
    (in1, in2, in3) => MapGlb(fun(a => fct(a))) $ in1 )

  // Test invalid 1D array with default local size
  @Test(expected=classOf[InvalidIndexSpaceException])
  def Indivisible1DRange(): Unit = {
    // Input Array
    val inputSize = 31
    val array = Array.fill(inputSize)(util.Random.nextFloat())

    // execute
    Execute(inputSize)(f, array) // should throw

    // explicit failure
    assert(assertion = false)
  }

  // Test invalid 1D array with explicit local size
  @Test(expected=classOf[InvalidIndexSpaceException])
  def Indivisible1DExplicitRange(): Unit = {
    // Input Array
    val inputSize = 499
    val array = Array.fill(inputSize)(util.Random.nextFloat())

    // execute
    Execute(100, inputSize)(f, array) // should throw

    // explicit failure
    assert(assertion = false)
  }

  // global size < local size
  @Test(expected=classOf[InvalidIndexSpaceException])
  def Invalid1DExplicitRange(): Unit = {
    // Input Array
    val inputSize = 64
    val array = Array.fill(inputSize)(util.Random.nextFloat())

    // execute
    Execute(inputSize)(f, array) // should throw

    // explicit failure
    assert(assertion = false)
  }

  // global size == 0
  @Test(expected=classOf[InvalidGlobalSizeException])
  def EmptyGlobalSize(): Unit = {
    // Input Array
    val inputSize = 128
    val array = Array.fill(inputSize)(util.Random.nextFloat())

    // execute
    Execute(0)(f, array) // should throw

    // explicit failure
    assert(assertion = false)
  }

  // global size < 0
  @Test(expected=classOf[InvalidGlobalSizeException])
  def NegativeGlobalSize(): Unit = {
    // Input Array
    val inputSize = 128
    val array = Array.fill(inputSize)(util.Random.nextFloat())

    // execute
    Execute(-inputSize)(f, array) // should throw

    // explicit failure
    assert(assertion = false)
  }

  // local size == 0
  @Test(expected=classOf[InvalidGlobalSizeException])
  def EmptyLocalSize(): Unit = {
    // Input Array
    val inputSize = 128
    val array = Array.fill(inputSize)(util.Random.nextFloat())

    // execute
    Execute(inputSize,0)(f, array) // should throw

    // explicit failure
    assert(assertion = false)
  }

  // local size == 0
  @Test(expected=classOf[InvalidGlobalSizeException])
  def NegativeLocalSize(): Unit = {
    // Input Array
    val inputSize = 128
    val array = Array.fill(inputSize)(util.Random.nextFloat())

    // execute
    Execute(inputSize,-inputSize)(f, array) // should throw

    // explicit failure
    assert(assertion = false)
  }

  @Test(expected=classOf[IllegalArgumentException])
  def WrongNumberOfArguments(): Unit = {
    // Input Array
    val inputSize = 128
    val array = Array.fill(inputSize)(util.Random.nextFloat())

    // execute
    Execute(inputSize)(f, array, array) // should throw

    // explicit failure
    assert(assertion = false)
  }

  @Test(expected=classOf[IllegalArgumentException])
  def WrongArgumentType(): Unit = {
    // Input Array
    val inputSize = 128
    val array = Array.fill(inputSize)(util.Random.nextInt())

    // execute
    Execute(inputSize)(f, array) // should throw

    // explicit failure
    assert(assertion = false)
  }

  @Test(expected=classOf[IllegalArgumentException])
  def NamesAndTypesDontMatchInUserFunDef(): Unit = {
    UserFunDef("inc", Array("x", "y"),
      " return x+1.0; ", Seq(Float), Float)
  }


  @Test(expected=classOf[IllegalArgumentException])
  def NamesAndTypesDontMatchInUserFunDef2(): Unit = {
    UserFunDef("inc", Array("x"),
      " return x+1.0; ", Seq(Float, Float), Float)
  }

  // Test invalid 2D space with explicit local size
  @Test(expected=classOf[InvalidIndexSpaceException])
  def Indivisible2DExplicitRange(): Unit = {
    // Input Array
    val inputSize = 499
    val array = Array.fill(inputSize)(util.Random.nextFloat())

    // execute
    Execute(100, 100, inputSize + 1, inputSize, (false, false))(f2, array, array) // should throw

    // explicit failure
    assert(assertion = false)
  }

  // global size < local size
  @Test(expected=classOf[InvalidIndexSpaceException])
  def Invalid2DExplicitRange(): Unit = {
    // Input Array
    val inputSize = 64
    val array = Array.fill(inputSize)(util.Random.nextFloat())

    // execute
    Execute(inputSize, 128*2, inputSize, inputSize, (false, false))(f2, array, array) // should throw

    // explicit failure
    assert(assertion = false)
  }


  // Test invalid 3D space with explicit local size
  @Test(expected=classOf[InvalidIndexSpaceException])
  def Indivisible3DExplicitRange(): Unit = {
    // Input Array
    val inputSize = 499
    val array = Array.fill(inputSize)(util.Random.nextFloat())

    // execute
    Execute(100, 100, 100, inputSize, inputSize, inputSize + 1, (false, false))(f3, array, array, array) // should throw

    // explicit failure
    assert(assertion = false)
  }

  // global size < local size
  @Test(expected=classOf[InvalidIndexSpaceException])
  def Invalid3DExplicitRange(): Unit = {
    // Input Array
    val inputSize = 64
    val array = Array.fill(inputSize)(util.Random.nextFloat())

    // execute
    Execute(inputSize, inputSize, inputSize*2, inputSize, inputSize, inputSize, (false, false)
    )(f3, array, array, array) // should throw

    // explicit failure
    assert(assertion = false)
  }
}
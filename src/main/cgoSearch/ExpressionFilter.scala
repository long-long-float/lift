package cgoSearch

import exploration.InferNDRange
import ir.ScalarType
import ir.ast.Lambda
import ir.view.View
import opencl.generator.OpenCLCodeGen
import opencl.ir._

object ExpressionFilter {
  object Status extends Enumeration {
    type Status = Value
    val Success,
    TooMuchPrivateMemory,
    TooMuchLocalMemory,
    NotEnoughWorkItems,
    TooManyWorkItems,
    NotEnoughWorkGroups,
    TooManyWorkGroups,
    NotEnoughParallelism,
    InternalException = Value
  }

  import ExpressionFilter.Status._

  def apply(expr: Lambda, values: Any*): Status = {
    try {
      // compute NDRange based on the parameters
      val (local, global) = InferNDRange(expr, values: _*)

      expr.params.foreach((p) => {
        p.t match {
          case _: ScalarType =>
            p.mem = OpenCLMemory.allocPrivateMemory(
              OpenCLMemory.getMaxSizeInBytes(p.t))
          case _ =>
            p.mem = OpenCLMemory.allocGlobalMemory(
              OpenCLMemory.getMaxSizeInBytes(p.t))
        }
        p.view = View(p.t, OpenCLCodeGen().toString(p.mem.variable))
      })

      OpenCLMemoryAllocator.alloc(expr.body)

      // Get the allocated buffers
      val buffers = TypedOpenCLMemory.get(expr.body, expr.params, includePrivate = true)

      // filter private memory
      val private_buffers_size = buffers.filter(_.mem.addressSpace == PrivateMemory)
      val private_alloc_size = private_buffers_size.map(_.mem.size).reduce(_ + _).eval
      if (private_alloc_size > SearchParameters.max_amount_private_memory ||
          private_buffers_size.forall(_.mem.size.eval <= 0)) {
        return TooMuchPrivateMemory
      }

      // filter local memory
      val local_buffers_size = buffers.filter(_.mem.addressSpace == LocalMemory)
      val local_alloc_size =
        if (local_buffers_size.nonEmpty)
          local_buffers_size.map(_.mem.size).reduce(_ + _).eval
        else 0
      if (local_alloc_size > 50000 ||
          local_buffers_size.forall(_.mem.size.eval <= 0)) {
        return TooMuchLocalMemory
      }

      // Rule out obviously poor choices based on the grid size
      // - minimum of workitems in a workgroup
      if (local.map(_.eval).product < SearchParameters.min_work_items) {
        return NotEnoughWorkItems
      }

      // - minimum size of the entire compute grid
      if (global.map(_.eval).product < SearchParameters.min_grid_size) {
        return NotEnoughWorkItems
      }

      // Avoid crashing for invalid values
      if (local.map(_.eval).product > 1024) {
        return TooManyWorkItems
      }

      // - minimum number of workgroups
      val num_workgroups = (global.map(_.eval) zip local.map(_.eval)).map(x => x._1 / x._2).product
      if (num_workgroups < SearchParameters.min_num_workgroups) {
        return NotEnoughWorkGroups
      }

      if (num_workgroups > SearchParameters.max_num_workgroups) {
        return TooManyWorkGroups
      }

      // This measures the % of max local memory / thread
      val resource_per_thread = if (local_alloc_size == 0) 0
      else 0
      /*  Executor.getDeviceLocalMemSize.toFloat /
          (Math.floor(Executor.getDeviceLocalMemSize / local_alloc_size) * local.map(_.eval).product.toFloat) /
          //                                                                   ^--- times # of work-items
          //                                                               ^--- # workgroup / sm
          //                                             ^--- usage per workgroup
          //              ^--- max local memory
          Executor.getDeviceLocalMemSize.toFloat * 100.0
      //  ^--- as a fraction of max mem            ^--- in %
*/
      // number of threads / SM
      if (resource_per_thread > SearchParameters.resource_per_thread) {
        return NotEnoughParallelism
      }

      // all good...
      Success
    } catch {
      case _: Throwable =>
        InternalException
    }
  }
}

// See LICENSE for license details.

package firrtl.passes

import firrtl._
import firrtl.ir._
import firrtl.Mappers._
import firrtl.PrimOps._
import firrtl.options.{Dependency, PreservesAll}

/** Handles dynamic accesses to zero-length vectors.
  *
  * @note Removes assignments that use a zero-length vector as a sink
  * @note Removes signals resulting from accesses to a zero-length vector from attach groups
  * @note Removes attaches that become degenerate after zero-length-accessor removal
  * @note Replaces "source" references to elements of zero-length vectors with zero
  */
object ZeroLengthVecs extends Pass with PreservesAll[Transform] {
  override val prerequisites =
    Seq( Dependency(PullMuxes),
         Dependency(ResolveKinds),
         Dependency(InferTypes),
         Dependency(ExpandConnects) )

  // Represents the results of a subaccess of a zero-length Vector
  private case class DontCareAggregate(tpe: AggregateType) extends Expression {
    def serialize: String = "DONTCARE"
    def mapExpr(f: Expression => Expression): Expression = this
    def mapType(f: Type => Type): Expression = this
    def mapWidth(f: Width => Width): Expression = this
    def foreachExpr(f: Expression => Unit): Unit = Unit
    def foreachType(f: Type => Unit): Unit = Unit
    def foreachWidth(f: Width => Unit): Unit = Unit
  }

  // Pass in an expression, not just a type, since it's not possible to generate an expression of
  // interval type with the type alone unless you declare a component
  // Big-picture TODO: zero for don't care is bad -- change this here when repo-wide refactor happens
  private def replaceWithDontCare(toReplace: Expression): Expression = toReplace.tpe match {
    case UIntType(w) => UIntLiteral(0, w)
    case SIntType(w) => SIntLiteral(0, w)
    case FixedType(w, p) => FixedLiteral(0, w, p)
    case at: AggregateType => DontCareAggregate(at)
    case it: IntervalType =>
      val zeroType = IntervalType(Closed(0), Closed(0), IntWidth(0))
      val zeroLit = DoPrim(AsInterval, Seq(SIntLiteral(0)), Seq(0, 0, 0), zeroType)
      DoPrim(Clip, Seq(zeroLit, toReplace), Nil, it)
  }

  private def zeroLenDerivedRefLike(expr: Expression): Boolean = (expr, expr.tpe) match {
    case (_, VectorType(_, 0)) => true
    case (WSubIndex(e, _, _, _), _) => zeroLenDerivedRefLike(e)
    case (WSubAccess(e, _, _, _), _) => zeroLenDerivedRefLike(e)
    case (WSubField(e, _, _, _), _) => zeroLenDerivedRefLike(e)
    case _ => false
  }

  // The connects have all been lowered, so all aggregate-typed expressions are "grounded" by WSubField/WSubAccess/WSubIndex
  // Map before matching because we want don't-cares to propagate UP expression trees
  private def dropZeroLenSubAccesses(expr: Expression): Expression = expr match {
    case _: WSubIndex | _: WSubAccess | _: WSubField =>
      if (zeroLenDerivedRefLike(expr)) replaceWithDontCare(expr) else expr
    case e => e map dropZeroLenSubAccesses
  }

  // Attach semantics: drop all zero-length-derived members of attach group, drop stmt if trivial
  private def onStmt(stmt: Statement): Statement = stmt match {
    case Connect(_, sink, _) if zeroLenDerivedRefLike(sink) => EmptyStmt
    case IsInvalid(_, sink) if zeroLenDerivedRefLike(sink) => EmptyStmt
    case Attach(info, sinks) =>
      val filtered = Attach(info, sinks.filterNot(zeroLenDerivedRefLike))
      if (filtered.exprs.length < 2) EmptyStmt else filtered
    case s => s.map(onStmt).map(dropZeroLenSubAccesses)
  }

  override def run(c: Circuit): Circuit = {
    c.copy(modules = c.modules.map(m => m.map(onStmt)))
  }
}
package effekt.atm

import Syntax.*

/** Monomorphic algebraic data, shared by the checker and Core.
 * Fields may have dependent refinements and closed, top-region function types.
 * All declarations are simultaneous, including mutually recursive types.
 */
object Datatypes {
  def validate(declarations: List[DataDeclaration]): List[DataDeclaration] = {
    def require(ok: Boolean, message: String): Unit = if !ok then throw Diagnostic(message)
    def names(xs: List[String], what: String): Unit = {
      require(xs.distinct.size == xs.size, s"Duplicate $what")
      require(xs.forall(_.matches("[A-Za-z_][A-Za-z0-9_]*")), s"Invalid $what name")
    }
    names(declarations.map(_.name), "datatype")
    val declared = declarations.map(_.name).toSet
    require((declared intersect Set("Int", "Bool", "Unit")).isEmpty, "A datatype cannot redefine a primitive type")
    names(declarations.flatMap(_.constructors.map(_.name)), "constructor")
    def field(t: Type): Type = t match {
      case Type.Int | Type.Bool | Type.Unit => t
      case Type.Pair(a, b) =>
        val pair = Type.Pair(field(a), field(b))
        require(Contracts.sort(pair).nonEmpty, "Store function fields directly, not inside pairs")
        pair
      case Type.Refined(n, b, p) => Type.Refined(n, field(b), p)
      case Type.Function(a, r, cs, region, n) if cs.isEmpty && region.forall(_ == Evidence.Top) && r.effect == Effect.Pure =>
        Type.Function(field(a), r.copy(value = field(r.value)), cs, Some(Evidence.Top), n)
      case Type.LocalFunction(a, r, cs, region, n) if cs.isEmpty && region.forall(_ == Evidence.Top) && r.effect == Effect.Pure =>
        Type.LocalFunction(field(a), r.copy(value = field(r.value)), cs, Some(Evidence.Top), n)
      case Type.Token(n) if declared(n) => Type.Data(n)
      case Type.Data(n) if declared(n) => t
      case _ => throw Diagnostic("Datatype fields require data or a closed pure top-region function type; found " + show(t))
    }
    val result = declarations.map { d =>
      require(d.constructors.nonEmpty, "A datatype needs at least one constructor")
      d.copy(constructors = d.constructors.map { c =>
        names(c.fields.map(_._1), "constructor field")
        c.copy(fields = c.fields.map((n, t) => (n, field(t))))
      })
    }
    // SMT algebraic datatypes must have finite inhabitants. Reject empty
    // recursive cycles rather than relying on solver-specific error handling.
    var inhabited = Set.empty[String]
    def finite(t: Type): Boolean = Contracts.base(t) match {
      case Type.Data(n) => inhabited(n)
      case Type.Pair(a, b) => finite(a) && finite(b)
      case _ => true
    }
    var changed = true
    while changed do {
      val next = inhabited ++ result.filter(_.constructors.exists(_.fields.forall(f => finite(f._2)))).map(_.name)
      changed = next != inhabited; inhabited = next
    }
    require(inhabited == declared, "Datatypes must have finite inhabitants: " + (declared -- inhabited).toList.sorted.mkString(", "))
    result
  }
  def constructors(ds: List[DataDeclaration]): Map[String, (String, DataConstructor)] =
    ds.flatMap(d => d.constructors.map(c => c.name -> (d.name, c))).toMap
  def logical(ds: List[DataDeclaration]): Map[String, List[(String, List[Logic.Sort])]] =
    ds.map(d => d.name -> d.constructors.map(c => c.name -> c.fields.map(f => logicalSort(f._2)))).toMap
  // Logical data is a projection: functions are represented by unit. No runtime
  // equality is admitted for types containing functions, even transitively.
  def logicalSort(t: Type): Logic.Sort = Contracts.base(t) match {
    case Type.Pair(a, b) => Logic.Sort.Pair(logicalSort(a), logicalSort(b))
    case _: Type.Function | _: Type.LocalFunction => Logic.Sort.Unit
    case other => Contracts.sort(other).get
  }
  def equalitySupported(t: Type, ds: List[DataDeclaration], seen: Set[String] = Set.empty): Boolean = Contracts.base(t) match {
    case Type.Data(n) if !seen(n) => ds.find(_.name == n).get.constructors.forall(_.fields.forall(f => equalitySupported(f._2, ds, seen + n)))
    case Type.Pair(a,b) => equalitySupported(a, ds, seen) && equalitySupported(b, ds, seen)
    case _: Type.Function | _: Type.LocalFunction | _: Type.Continuation => false
    case _ => true
  }
  def expression(name: String, args: List[Logic.Expr]): Logic.Expr = Logic.Expr.App("constructor:" + name, args)
}

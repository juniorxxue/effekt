package effekt.atm

import Syntax.*

/** Syntax elaborations into the existing unary/value source calculus. */
object Surface {
  def total(v: Value): Boolean = v match {
    case Value.Primitive("div" | "mod", _) => false
    case Value.Primitive(_, args) => args.forall(total)
    case Value.Constructor(_, args) => args.forall(total)
    case _ => true
  }
  def tuple(types: List[Type]): Type = types match {
    case Nil => Type.Unit
    case t :: Nil => t
    case t :: ts => Type.Pair(t, tuple(ts))
  }
  def arguments(values: List[Value]): Value = values match {
    case Nil => Value.Unit
    case v :: Nil => v
    case v :: vs => Value.Primitive("pair", List(v, arguments(vs)))
  }
  def components(t: Type, size: Int): List[Type] = (size, Contracts.base(t)) match {
    case (0, _) => Nil
    case (1, _) => List(t)
    case (n, Type.Pair(a, b)) => a :: components(b, n - 1)
    case _ => throw Diagnostic("Malformed tuple parameter type")
  }
  def projection(v: Value, index: Int, size: Int): Value =
    if size == 1 then v
    else if index == 0 then Value.Primitive("fst", List(v))
    else projection(Value.Primitive("snd", List(v)), index - 1, size - 1)
  def projection(v: Logic.Expr, index: Int, size: Int): Logic.Expr =
    if size == 1 then v
    else if index == 0 then Logic.Expr.App("fst", List(v))
    else projection(Logic.Expr.App("snd", List(v)), index - 1, size - 1)

  // Source-level type arguments respect nested type binders. The checker
  // separately instantiates resolved, generatively named interface signatures.
  def typeArguments(t: Type, actuals: Map[String, Type]): Type = new Annotations.Mapper {
    override def typ(t: Type): Type = t match {
      case Type.Token(n) if actuals.contains(n) => actuals(n)
      case Type.Interface(n, Nil) if actuals.contains(n) => actuals(n)
      case Type.TypePoly(n, b) => Type.TypePoly(n, typeArguments(b, actuals - n))
      case Type.KindPoly(n, k, b) => Type.KindPoly(n, k, typeArguments(b, actuals - n))
      case _ => super.typ(t)
    }
  }.typ(t)

  // Used to recognize ordinary recursive definitions, respecting lexical
  // shadowing. An operation's capability scopes only over its handled body.
  def free(v: Value): Set[String] = v match {
    case Value.Variable(n) => Set(n)
    case Value.Capability(n) => Set(n)
    case Value.Lambda(n, _, _, b, _) => free(b) - n
    case Value.ContextLambda(n, _, _, _, b, _) => free(b) - n
    case Value.InferredLambda(n, _, b, _) => free(b) - n
    case Value.UnannotatedLambda(n, _, b, _) => free(b) - n
    case Value.FixedLambda(n, _, _, b, _) => free(b) - n
    case Value.CapabilityLambda(n, _, b, _) => free(b) - n
    case Value.Instantiate(f, a) => free(f) ++ free(a)
    case Value.InstantiateType(f, _) => free(f)
    case Value.InstantiateKind(f, _) => free(f)
    case Value.InstantiatePolymorphic(f, _) => free(f)
    case Value.InstantiateEffect(f, _) => free(f)
    case Value.InstantiatePredicate(f, _, _) => free(f)
    case Value.TypeLambda(_, b) => free(b)
    case Value.KindLambda(_, _, b) => free(b)
    case Value.EffectLambda(_, b, _) => free(b)
    case Value.PredicateLambda(_, _, b) => free(b)
    case Value.Primitive(_, args) => args.flatMap(free).toSet
    case Value.Constructor(_, args) => args.flatMap(free).toSet
    case _ => Set.empty
  }
  def free(t: Term): Set[String] = t match {
    case Term.Return(v) => free(v)
    case Term.Let(n, a, b) => free(a) ++ (free(b) - n)
    case Term.LetRec(n, p, _, _, d, b, _, _) => (free(d) -- Set(n, p)) ++ (free(b) - n)
    case Term.Apply(f, a) => free(f) ++ free(a)
    case Term.Perform(c, _, a) => free(a) + c
    case Term.AnnotatedPerform(c, _, a, _, _) => free(a) + c
    case Term.SingleAnnotatedPerform(c, _, a, _) => free(a) + c
    case Term.Ascribe(b, _) => free(b)
    case Term.TypeAlias(_, _, b) => free(b)
    case Term.AnnotationAlias(_, _, b) => free(b)
    case Term.ExplicitReturn(b) => free(b)
    case Term.If(c, a, b) => free(c) ++ free(a) ++ free(b)
    case Term.Assert(c) => free(c)
    case Term.Match(v, cs) => free(v) ++ cs.flatMap(c => free(c.body) -- c.parameters)
    case Term.Handle(c, _, ops, b, n, ret) => handlerFree(c, ops, b, n, ret)
    case Term.InferredHandle(c, ops, b, n, ret) => handlerFree(c, ops, b, n, ret)
  }
  private def handlerFree(c: String, ops: List[Operation], b: Term, n: String, ret: Term): Set[String] =
    (free(b) - c) ++ (free(ret) - n) ++ ops.flatMap(o => free(o.body) -- Set(o.parameter, o.continuation))
}

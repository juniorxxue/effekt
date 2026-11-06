package effekt.atm

import Syntax.*
import Logic.Expr

/** Traversals shared by dependent, type, predicate, effect and capability
 * substitution. Every checked binder is generative before substitution.
 */
object Contracts {
  def map(t: Type)(f: Type => Type, expression: Expr => Expr = identity): Type = {
    def ty(t: Type): Type = map(t)(f, expression)
    def comp(c: Computation): Computation = c.copy(value = ty(c.value), effect = eff(c.effect))
    def eff(e: Effect): Effect = e match {
      case Effect.Change(a, b) => Effect.Change(comp(a), comp(b))
      case Effect.Sequence(a, b) => Effect.Sequence(eff(a), eff(b))
      case e => e
    }
    def bound(b: EffectBound): EffectBound = b match {
      case EffectBound.After(e) => EffectBound.After(eff(e))
      case b => b
    }
    f(t match {
      case Type.Pair(a, b) => Type.Pair(ty(a), ty(b))
      case Type.Refined(n, b, p) => Type.Refined(n, ty(b), expression(p))
      case Type.Function(a, r, cs, scope, name) => Type.Function(ty(a), comp(r), cs, scope, name)
      case Type.LocalFunction(a, r, cs, scope, name) => Type.LocalFunction(ty(a), comp(r), cs, scope, name)
      case Type.Continuation(a, r, region, cs, name) => Type.Continuation(ty(a), comp(r), region, cs, name)
      case Type.TypePoly(n, b) => Type.TypePoly(n, ty(b))
      case Type.PredicatePoly(n, args, b) => Type.PredicatePoly(n, args, ty(b))
      case Type.EffectPoly(n, b, domain) => Type.EffectPoly(n, ty(b), bound(domain))
      case Type.CapabilityPoly(n, ops, b, cs, scope, obligations) =>
        Type.CapabilityPoly(n, ops.map(o => o.copy(argument = ty(o.argument), result = ty(o.result),
          initialAnswer = comp(o.initialAnswer), finalAnswer = comp(o.finalAnswer))), ty(b), cs, scope, obligations)
      case other => other
    })
  }
  def map(c: Computation)(f: Type => Type, expression: Expr => Expr): Computation =
    c.copy(value = map(c.value)(f, expression), effect = map(c.effect)(f, expression))
  def map(e: Effect)(f: Type => Type, expression: Expr => Expr): Effect = e match {
    case Effect.Change(a, b) => Effect.Change(map(a)(f, expression), map(b)(f, expression))
    case Effect.Sequence(a, b) => Effect.Sequence(map(a)(f, expression), map(b)(f, expression))
    case other => other
  }
  def substitute(t: Type, name: String, value: Expr): Type =
    map(t)(identity, Logic.substitute(_, name, value))
  def substitute(c: Computation, name: String, value: Expr): Computation =
    map(c)(identity, Logic.substitute(_, name, value))
  def instantiate(t: Type, name: String, actual: Type): Type = map(t)({
    case Type.Variable(n) if n == name => actual
    case other => other
  })
  def instantiatePredicate(t: Type, name: String, parameters: List[String], body: Expr): Type =
    map(t)(identity, Logic.instantiate(_, name, parameters, body))
  def erase(t: Type): Type = map(t)({
    case Type.Refined(_, b, _) => b
    case Type.Function(a, r, cs, scope, _) => Type.Function(a, r, cs, scope)
    case Type.LocalFunction(a, r, cs, scope, _) => Type.LocalFunction(a, r, cs, scope)
    case Type.Continuation(a, r, region, cs, _) => Type.Continuation(a, r, region, cs)
    case other => other
  })
  def base(t: Type): Type = t match {
    case Type.Refined(_, b, _) => base(b)
    case other => other
  }
  def typeOfSort(s: Logic.Sort): Type = s match {
    case Logic.Sort.Int => Type.Int
    case Logic.Sort.Bool => Type.Bool
    case Logic.Sort.Unit => Type.Unit
    case Logic.Sort.Data(n) => Type.Data(n)
    case Logic.Sort.Pair(a, b) => Type.Pair(typeOfSort(a), typeOfSort(b))
  }
  def sort(t: Type): Option[Logic.Sort] = base(t) match {
    case Type.Pair(a, b) => for x <- sort(a); y <- sort(b) yield Logic.Sort.Pair(x, y)
    case Type.Int => Some(Logic.Sort.Int)
    case Type.Bool => Some(Logic.Sort.Bool)
    case Type.Unit => Some(Logic.Sort.Unit)
    case Type.Data(n) => Some(Logic.Sort.Data(n))
    case _ => None
  }
  def member(t: Type, value: Expr): Expr = t match {
    case Type.Pair(a, b) => Logic.and(member(a, Expr.App("fst", List(value))), member(b, Expr.App("snd", List(value))))
    case Type.Refined(n, b, p) => Logic.and(member(b, value), Logic.substitute(p, n, value))
    case _ => Logic.True
  }
  def free(t: Type): Set[String] = t match {
    case Type.Pair(a, b) => free(a) ++ free(b)
    case Type.Refined(n, b, p) => free(b) ++ (Logic.variables(p) - n)
    case Type.Function(a, r, _, _, name) => free(a) ++ (free(r) -- name)
    case Type.LocalFunction(a, r, _, _, name) => free(a) ++ (free(r) -- name)
    case Type.Continuation(a, r, _, _, name) => free(a) ++ (free(r) -- name)
    case Type.TypePoly(_, b) => free(b)
    case Type.PredicatePoly(_, _, b) => free(b)
    case Type.EffectPoly(_, b, domain) => free(b) ++ (domain match {
      case EffectBound.After(e) => free(e)
      case _ => Set.empty[String]
    })
    case Type.CapabilityPoly(_, ops, b, _, _, obligations) => free(b) ++ ops.flatMap { o =>
      free(o.argument) ++ ((free(o.result) ++ (free(o.initialAnswer) -- o.returned) ++ free(o.finalAnswer)) -- o.parameter)
    }
    case _ => Set.empty
  }
  def free(c: Computation): Set[String] = free(c.value) ++ (c.effect match {
    case Effect.Change(initial, result) => (free(initial) -- c.returned) ++ free(result)
    case other => free(other)
  })
  def free(e: Effect): Set[String] = e match {
    case Effect.Change(a, b) => free(a) ++ free(b)
    case Effect.Sequence(a, b) => free(a) ++ free(b)
    case _ => Set.empty
  }

  def usesRefinements(p: Program): Boolean = {
    var found = p.measures.nonEmpty
    def ty(t: Type): Unit = { map(t) { x =>
      x match {
        case _: Type.Refined | _: Type.PredicatePoly => found = true
        case Type.Function(_, _, _, _, Some(_)) | Type.LocalFunction(_, _, _, _, Some(_)) => found = true
        case _ => ()
      }; x
    }; () }
    def comp(c: Computation): Unit = { ty(c.value); effect(c.effect) }
    def effect(e: Effect): Unit = e match {
      case Effect.Change(a, b) => comp(a); comp(b)
      case Effect.Sequence(a, b) => effect(a); effect(b)
      case _ => ()
    }
    def value(v: Value): Unit = v match {
      case Value.Lambda(_, a, r, b) => ty(a); comp(r); term(b)
      case Value.LocalLambda(_, a, r, b, _) => a.foreach(ty); r.foreach(comp); term(b)
      case Value.InferredLambda(_, a, b) => ty(a); term(b)
      case Value.UnannotatedLambda(_, r, b) => r.foreach(comp); term(b)
      case Value.CapabilityLambda(_, ops, b, _) => ops.foreach(signature); value(b)
      case Value.Instantiate(f, c) => value(f); value(c)
      case Value.EffectLambda(_, b, bound) =>
        bound match { case EffectBound.After(e) => effect(e); case _ => () }
        value(b)
      case Value.InstantiateEffect(f, e) => value(f); effect(e)
      case Value.TypeLambda(_, b) => value(b)
      case Value.InstantiateType(f, t) => value(f); ty(t)
      case Value.PredicateLambda(_, _, b) => found = true; value(b)
      case Value.InstantiatePredicate(f, _, _) => found = true; value(f)
      case Value.Constructor(_, args) => args.foreach(value)
      case Value.Primitive(name, args) =>
        if name == "div" || name == "mod" then found = true
        args.foreach(value)
      case _ => ()
    }
    def signature(o: Signature): Unit = { if o.returned.nonEmpty then found = true; ty(o.argument); ty(o.result); comp(o.initialAnswer); comp(o.finalAnswer) }
    def term(t: Term): Unit = t match {
      case Term.TypeAlias(_, definition, body) => ty(definition); term(body)
      case Term.Return(v) => value(v)
      case Term.Assert(v) => found = true; value(v)
      case Term.Ascribe(b, c) => comp(c); term(b)
      case Term.ExplicitReturn(b) => term(b)
      case Term.LetRec(_, _, a, r, d, b, _) => ty(a); comp(r); term(d); term(b)
      case Term.Let(_, a, b) => term(a); term(b)
      case Term.Apply(f, a) => value(f); value(a)
      case Term.If(c, a, b) => value(c); term(a); term(b)
      case Term.Match(v, cases) => found = true; value(v); cases.foreach(c => term(c.body))
      case Term.Perform(_, _, a) => value(a)
      case Term.Handle(_, r, ops, b, _, ret) => comp(r); ops.foreach(o => { signature(o.signature); term(o.body) }); term(b); term(ret)
      case Term.InferredHandle(_, ops, b, _, ret) => ops.foreach(o => { signature(o.signature); term(o.body) }); term(b); term(ret)
    }
    p.datatypes.foreach(_.constructors.foreach(_.fields.foreach(f => ty(f._2))))
    comp(p.result); term(p.body); found
  }
}

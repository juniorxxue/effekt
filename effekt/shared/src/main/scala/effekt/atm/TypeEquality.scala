package effekt.atm

import Syntax.*

/** Structural equality modulo bound names, shared by checking and
 * answer-tag selection. Free capability/region identities stay exact.
 */
object TypeEquality {
  def same(a: Type, b: Type): Boolean = value(a, b, Binders(), Binders())
  def same(a: Computation, b: Computation): Boolean = computation(a, b, Binders(), Binders())
  def sameContext(a: Evidence.AnswerContext, b: Evidence.AnswerContext): Boolean = context(a, b, Binders(), Binders())
  def same(a: Effect, b: Effect): Boolean = effect(a, b, Binders(), Binders())
  def sameOperations(a: List[Signature], b: List[Signature]): Boolean = operations(a, b, Binders(), Binders())

  private case class Binders(capabilities: List[String] = Nil, effects: List[String] = Nil, types: List[String] = Nil, predicates: List[String] = Nil, terms: List[String] = Nil, annotations: List[String] = Nil)
  // An unnamed parameter still occupies a binder position. Omitting that slot
  // could identify an outer dependency on one side with an inner one on the other.
  // The empty sentinel cannot occur as a resolved source identifier.
  private def bind(parameter: Option[String], terms: List[String]): List[String] = parameter.getOrElse("") :: terms
  private def key(name: String, bound: List[String]): Either[String, Int] = bound.indexOf(name) match {
    case -1 => Left(name)
    case index => Right(index)
  }
  private def value(a: Type, b: Type, left: Binders, right: Binders): Boolean = (a, b) match {
    case (Type.Pair(a, b), Type.Pair(c, d)) => value(a, c, left, right) && value(b, d, left, right)
    case (Type.Variable(x), Type.Variable(y)) => key(x, left.types) == key(y, right.types)
    case (Type.Refined(x, a, p), Type.Refined(y, b, q)) =>
      value(a, b, left, right) && formula(p, q, left.copy(terms = x :: left.terms), right.copy(terms = y :: right.terms))
    case (Type.KindPoly(x, k, a), Type.KindPoly(y, l, b)) =>
      kind(k, l, left, right) && value(a, b, bindKind(x, k, left), bindKind(y, l, right))
    case (Type.AbstractResult(a), Type.AbstractResult(b)) => form(a, b, left, right)
    case (Type.TypePoly(x, a), Type.TypePoly(y, b)) =>
      value(a, b, left.copy(types = x :: left.types), right.copy(types = y :: right.types))
    case (Type.PredicatePoly(x, args, a), Type.PredicatePoly(y, params, b)) =>
      args == params && value(a, b, left.copy(predicates = x :: left.predicates), right.copy(predicates = y :: right.predicates))
    case (Type.Capability(x), Type.Capability(y)) => key(x, left.capabilities) == key(y, right.capabilities)
    case (Type.Continuation(a, c, r, cs, p), Type.Continuation(b, d, s, ds, q)) =>
      r == s && value(a, b, left, right) && computation(c, d,
        left.copy(terms = bind(p, left.terms)), right.copy(terms = bind(q, right.terms)))
    case (Type.LocalFunction(a, c, _, r, p), Type.LocalFunction(b, d, _, s, q)) =>
      value(a, b, left, right) && computation(c, d,
        left.copy(terms = bind(p, left.terms)), right.copy(terms = bind(q, right.terms))) &&
        r.map(key(_, left.capabilities)) == s.map(key(_, right.capabilities))
    case (Type.Function(arg, result, cs, scope, parameter), Type.Function(arg2, result2, cs2, scope2, parameter2)) =>
      value(arg, arg2, left, right) && computation(result, result2,
        left.copy(terms = bind(parameter, left.terms)), right.copy(terms = bind(parameter2, right.terms))) &&
        scope.map(key(_, left.capabilities)) == scope2.map(key(_, right.capabilities))
    case (Type.CapabilityPoly(x, ops, body, cs, scope, obligations), Type.CapabilityPoly(y, ops2, body2, ds, target, obligations2)) =>
      obligations.map(o => (key(o.constraint.from, x :: left.capabilities), key(o.constraint.to, x :: left.capabilities))).toSet ==
        obligations2.map(o => (key(o.constraint.from, y :: right.capabilities), key(o.constraint.to, y :: right.capabilities))).toSet &&
        operations(ops, ops2, left.copy(capabilities = x :: left.capabilities), right.copy(capabilities = y :: right.capabilities)) &&
        scope.map(key(_, left.capabilities)) == target.map(key(_, right.capabilities)) &&
        value(body, body2, left.copy(capabilities = x :: left.capabilities), right.copy(capabilities = y :: right.capabilities))
    case (Type.EffectPoly(x, body, bound), Type.EffectPoly(y, body2, bound2)) =>
      constraint(bound, bound2, left, right) &&
        value(body, body2, left.copy(effects = x :: left.effects), right.copy(effects = y :: right.effects))
    case _ => a == b
  }
  private def formula(a: Logic.Expr, b: Logic.Expr, left: Binders, right: Binders): Boolean = (a, b) match {
    case (Logic.Expr.Variable(x), Logic.Expr.Variable(y)) => key(x, left.terms) == key(y, right.terms)
    case (Logic.Expr.Predicate(x, as), Logic.Expr.Predicate(y, bs)) =>
      key(x, left.predicates) == key(y, right.predicates) && as.size == bs.size && as.zip(bs).forall((a, b) => formula(a, b, left, right))
    case (Logic.Expr.App(x, as), Logic.Expr.App(y, bs)) =>
      x == y && as.size == bs.size && as.zip(bs).forall((a, b) => formula(a, b, left, right))
    case (Logic.Expr.Exists(x, s, a), Logic.Expr.Exists(y, t, b)) =>
      s == t && formula(a, b, left.copy(terms = x :: left.terms), right.copy(terms = y :: right.terms))
    case (Logic.Expr.Forall(x, s, a), Logic.Expr.Forall(y, t, b)) =>
      s == t && formula(a, b, left.copy(terms = x :: left.terms), right.copy(terms = y :: right.terms))
    case _ => a == b
  }
  private def bindKind(n: String, k: Annotations.Kind, b: Binders): Binders = k match {
    case Annotations.Kind.Region => b.copy(capabilities = n :: b.capabilities)
    case _ => b.copy(annotations = n :: b.annotations)
  }
  private def distance(a: Evidence.Distance, b: Evidence.Distance, left: Binders, right: Binders): Boolean = {
    def rename(d: Evidence.Distance, bs: Binders): Evidence.Distance = d match {
      case Evidence.Distance.Variable(n) => Evidence.Distance.Variable(key(n, bs.annotations).toString)
      case Evidence.Distance.Plus(a, b) => Evidence.Distance.Plus(rename(a, bs), rename(b, bs))
      case _ => d
    }
    Evidence.sameDistance(rename(a, left), rename(b, right))
  }
  private def kind(a: Annotations.Kind, b: Annotations.Kind, left: Binders, right: Binders): Boolean = (a, b) match {
    case (Annotations.Kind.Context(d), Annotations.Kind.Context(e)) => distance(d, e, left, right)
    case (Annotations.Kind.Evidence(a, d, b), Annotations.Kind.Evidence(c, e, f)) =>
      key(a, left.capabilities) == key(c, right.capabilities) && key(b, left.capabilities) == key(f, right.capabilities) && distance(d, e, left, right)
    case _ => a == b
  }
  private def context(a: Evidence.AnswerContext, b: Evidence.AnswerContext, left: Binders, right: Binders): Boolean = (a, b) match {
    case (Evidence.AnswerContext.Hole, Evidence.AnswerContext.Hole) => true
    case (Evidence.AnswerContext.Variable(a), Evidence.AnswerContext.Variable(b)) => key(a, left.annotations) == key(b, right.annotations)
    case (Evidence.AnswerContext.Compose(a, b), Evidence.AnswerContext.Compose(c, d)) => context(a, c, left, right) && context(b, d, left, right)
    case (Evidence.AnswerContext.Layer(v, i, r, n), Evidence.AnswerContext.Layer(w, j, s, m)) =>
      value(v, w, left, right) && computation(i, j, left.copy(terms = bind(n, left.terms)), right.copy(terms = bind(m, right.terms))) && context(r, s, left, right)
    case _ => false
  }
  private def form(a: ComputationForm, b: ComputationForm, left: Binders, right: Binders): Boolean = (a, b) match {
    case (ComputationForm.Variable(a), ComputationForm.Variable(b)) => key(a, left.annotations) == key(b, right.annotations)
    case (ComputationForm.Context(a, c), ComputationForm.Context(b, d)) => context(a, b, left, right) && computation(c, d, left, right)
    case _ => false
  }
  private def computation(a: Computation, b: Computation, left: Binders, right: Binders): Boolean = (a.form, b.form) match {
    case (Some(a), Some(b)) => form(a, b, left, right)
    case (None, None) => value(a.value, b.value, left, right) && ((a.effect, b.effect) match {
      case (Effect.Change(ai, af), Effect.Change(bi, bf)) =>
        computation(ai, bi, left.copy(terms = bind(a.returned, left.terms)), right.copy(terms = bind(b.returned, right.terms))) &&
          computation(af, bf, left, right)
      case _ => effect(a.effect, b.effect, left, right)
    })
    case _ => false
  }

  private def effect(a: Effect, b: Effect, left: Binders, right: Binders): Boolean = (a, b) match {
    case (Effect.Pure, Effect.Pure) => true
    case (Effect.Variable(x), Effect.Variable(y)) => key(x, left.effects) == key(y, right.effects)
    case (Effect.Change(ai, af), Effect.Change(bi, bf)) =>
      computation(ai, bi, left, right) && computation(af, bf, left, right)
    case (Effect.Sequence(a, b), Effect.Sequence(c, d)) =>
      effect(a, c, left, right) && effect(b, d, left, right)
    case _ => false
  }
  def same(a: EffectBound, b: EffectBound): Boolean = constraint(a, b, Binders(), Binders())
  private def constraint(a: EffectBound, b: EffectBound, left: Binders, right: Binders): Boolean = (a, b) match {
    case (EffectBound.After(x), EffectBound.After(y)) => effect(x, y, left, right)
    case _ => a == b
  }
  private def operations(a: List[Signature], b: List[Signature], left: Binders, right: Binders): Boolean =
    a.size == b.size && a.sortBy(_.name).zip(b.sortBy(_.name)).forall { (x, y) =>
      val lp = left.copy(terms = bind(x.parameter, left.terms))
      val rp = right.copy(terms = bind(y.parameter, right.terms))
      val lr = lp.copy(terms = bind(x.returned, lp.terms))
      val rr = rp.copy(terms = bind(y.returned, rp.terms))
      x.name == y.name && value(x.argument, y.argument, left, right) &&
        value(x.result, y.result, lp, rp) && computation(x.initialAnswer, y.initialAnswer, lr, rr) &&
        computation(x.finalAnswer, y.finalAnswer, lp, rp)
    }
}

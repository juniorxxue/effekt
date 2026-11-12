package effekt.atm

import Syntax.*

/** Substitution for answer-effect variables. Capability binders and delimiter
 * contexts are preserved; nested effect binders shadow the substituted name.
 */
object EffectSubstitution {
  private def mapper(variable: String, replacement: Effect): Annotations.Mapper = new Annotations.Mapper {
    override def typ(t: Type): Type = t match {
      case Type.EffectPoly(n, body, domain) if n == variable => Type.EffectPoly(n, body, bound(domain))
      case _ => super.typ(t)
    }
    override def effect(e: Effect): Effect = e match {
      case Effect.Variable(n) if n == variable => replacement
      case Effect.Sequence(a, b) => EffectComposition(effect(a), effect(b))
      case _ => super.effect(e)
    }
  }
  def apply(tpe: Type, variable: String, replacement: Effect): Type = mapper(variable, replacement).typ(tpe)
  def apply(tpe: Computation, variable: String, replacement: Effect): Computation = mapper(variable, replacement).computation(tpe)
  def apply(effect: Effect, variable: String, replacement: Effect): Effect = mapper(variable, replacement).effect(effect)
  def apply(bound: EffectBound, variable: String, replacement: Effect): EffectBound = bound match {
    case EffectBound.After(prefix) => EffectBound.After(apply(prefix, variable, replacement))
    case other => other
  }

  def free(tpe: Type): Set[String] = tpe match {
    case Type.Function(arg, result, _, _, _, _) => free(arg) ++ free(result)
    case Type.FixedFunction(arg, result, _, _, _) => free(arg) ++ free(result)
    case Type.Continuation(arg, result, _, _, _) => free(arg) ++ free(result)
    case Type.Pair(a, b) => free(a) ++ free(b)
    case Type.TypePoly(_, body) => free(body)
    case Type.KindPoly(_, _, body) => free(body)
    case Type.AbstractResult(f) => free(Annotations.abstractComputation(f))
    case Type.PredicatePoly(_, _, body) => free(body)
    case Type.Refined(_, base, _) => free(base)
    case Type.CapabilityPoly(_, ops, body, _, _, obligations) => free(body) ++ ops.flatMap { op =>
      free(op.argument) ++ free(op.result) ++ free(op.initialAnswer) ++ free(op.finalAnswer)
    }
    case Type.EffectPoly(name, body, bound) => (free(body) - name) ++ free(bound)
    case _ => Set.empty
  }
  def free(tpe: Computation): Set[String] = tpe.form match {
    case Some(ComputationForm.Variable(_)) => Set.empty
    case Some(ComputationForm.Context(c, a)) => freeContext(c) ++ free(a)
    case None => free(tpe.value) ++ free(tpe.effect)
  }
  private def freeContext(c: Evidence.AnswerContext): Set[String] = c match {
    case Evidence.AnswerContext.Layer(v, i, r, _) => free(v) ++ free(i) ++ freeContext(r)
    case Evidence.AnswerContext.Compose(a, b) => freeContext(a) ++ freeContext(b)
    case _ => Set.empty
  }
  def free(effect: Effect): Set[String] = effect match {
    case Effect.Pure | Effect.Abstract => Set.empty
    case Effect.Variable(name) => Set(name)
    case Effect.Change(a, b) => free(a) ++ free(b)
    case Effect.Sequence(a, b) => free(a) ++ free(b)
  }
  def free(bound: EffectBound): Set[String] = bound match {
    case EffectBound.After(prefix) => free(prefix)
    case _ => Set.empty
  }
}

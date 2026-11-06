package effekt.atm

import Syntax.*

/** Substitution for answer-effect variables. Capability binders and delimiter
 * contexts are preserved; nested effect binders shadow the substituted name.
 */
object EffectSubstitution {
  def apply(tpe: Type, variable: String, replacement: Effect): Type = tpe match {
    case Type.Function(arg, result, cs, scope, parameter) =>
      Type.Function(apply(arg, variable, replacement), apply(result, variable, replacement), cs, scope, parameter)
    case Type.LocalFunction(arg, result, cs, region, parameter) =>
      Type.LocalFunction(apply(arg, variable, replacement), apply(result, variable, replacement), cs, region, parameter)
    case Type.Continuation(arg, result, region, cs, parameter) =>
      Type.Continuation(apply(arg, variable, replacement), apply(result, variable, replacement), region, cs, parameter)
    case Type.Pair(a, b) => Type.Pair(apply(a, variable, replacement), apply(b, variable, replacement))
    case Type.TypePoly(name, body) => Type.TypePoly(name, apply(body, variable, replacement))
    case Type.PredicatePoly(name, args, body) => Type.PredicatePoly(name, args, apply(body, variable, replacement))
    case Type.Refined(name, base, predicate) => Type.Refined(name, apply(base, variable, replacement), predicate)
    case Type.CapabilityPoly(name, ops, body, captures, context, obligations) =>
      Type.CapabilityPoly(name, ops.map { op => op.copy(
        argument = apply(op.argument, variable, replacement), result = apply(op.result, variable, replacement),
        initialAnswer = apply(op.initialAnswer, variable, replacement), finalAnswer = apply(op.finalAnswer, variable, replacement))
      }, apply(body, variable, replacement), captures, context, obligations)
    case Type.EffectPoly(name, body, bound) =>
      Type.EffectPoly(name, if name == variable then body else apply(body, variable, replacement),
        apply(bound, variable, replacement))
    case _ => tpe
  }
  def apply(tpe: Computation, variable: String, replacement: Effect): Computation =
    tpe.copy(value = apply(tpe.value, variable, replacement), effect = apply(tpe.effect, variable, replacement))
  def apply(effect: Effect, variable: String, replacement: Effect): Effect = effect match {
      case Effect.Variable(name) if name == variable => replacement
      case Effect.Change(a, b) => Effect.Change(apply(a, variable, replacement), apply(b, variable, replacement))
      case Effect.Sequence(a, b) => EffectComposition(apply(a, variable, replacement), apply(b, variable, replacement))
      case e => e
  }
  def apply(bound: EffectBound, variable: String, replacement: Effect): EffectBound = bound match {
    case EffectBound.After(prefix) => EffectBound.After(apply(prefix, variable, replacement))
    case other => other
  }

  def free(tpe: Type): Set[String] = tpe match {
    case Type.Function(arg, result, _, _, _) => free(arg) ++ free(result)
    case Type.LocalFunction(arg, result, _, _, _) => free(arg) ++ free(result)
    case Type.Continuation(arg, result, _, _, _) => free(arg) ++ free(result)
    case Type.Pair(a, b) => free(a) ++ free(b)
    case Type.TypePoly(_, body) => free(body)
    case Type.PredicatePoly(_, _, body) => free(body)
    case Type.Refined(_, base, _) => free(base)
    case Type.CapabilityPoly(_, ops, body, _, _, obligations) => free(body) ++ ops.flatMap { op =>
      free(op.argument) ++ free(op.result) ++ free(op.initialAnswer) ++ free(op.finalAnswer)
    }
    case Type.EffectPoly(name, body, bound) => (free(body) - name) ++ free(bound)
    case _ => Set.empty
  }
  def free(tpe: Computation): Set[String] = free(tpe.value) ++ free(tpe.effect)
  def free(effect: Effect): Set[String] = effect match {
    case Effect.Pure => Set.empty
    case Effect.Variable(name) => Set(name)
    case Effect.Change(a, b) => free(a) ++ free(b)
    case Effect.Sequence(a, b) => free(a) ++ free(b)
  }
  def free(bound: EffectBound): Set[String] = bound match {
    case EffectBound.After(prefix) => free(prefix)
    case _ => Set.empty
  }
}

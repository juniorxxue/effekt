package effekt.atm

import Syntax.*

/** Partial, ordered composition in program execution order.
 * Unknown compositions are retained as expressions, not asserted to be defined.
 * The checker must justify them using its bounds before admitting a type/term.
 */
object EffectComposition {
  def apply(first: Effect, second: Effect): Effect = (first, second) match {
    case (Effect.Pure, e) => e
    case (e, Effect.Pure) => e
    case (Effect.Change(middle, result), Effect.Change(initial, middle2)) =>
      if !TypeEquality.same(middle, middle2) then
        throw Diagnostic("Sequential answer types do not compose")
      Effect.Change(initial, result)
    case (Effect.Sequence(a, b), c) => apply(a, apply(b, c))
    case _ => Effect.Sequence(first, second)
  }

  def elements(effect: Effect): List[Effect] = effect match {
    case Effect.Pure => Nil
    case Effect.Sequence(a, b) => elements(a) ++ elements(b)
    case other => List(other)
  }
}

package effekt.atm

/** Browser callers can supply a solver through Checker.check's solver argument. */
object RefinementSolver {
  val default: Logic.Solver = new Logic.Solver {
    def prove(context: Logic.Context, conclusion: Logic.Expr): Logic.Verdict =
      Logic.Verdict.Unknown("Refinement checking requires a solver; use the JVM CLI or supply Logic.Solver")
  }
}

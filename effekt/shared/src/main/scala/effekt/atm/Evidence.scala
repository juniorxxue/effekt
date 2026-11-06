package effekt.atm

import Syntax.*

/** Figures 9 and 15 of the supplemental algorithm. Regions are generative
 * names, not positions in a handler stack. Residuals are tagged assumptions;
 * their distances stay symbolic until an abstraction supplies evidence.
 */
object Evidence {
  val Top = "top#region"
  case class Constraint(from: String, to: String) {
    def free: Set[String] = Set(from, to) - Top
    def substitute(name: String, region: String): Constraint =
      Constraint(if from == name then region else from, if to == name then region else to)
  }
  enum Distance {
    case Numeral(value: Int)
    case Variable(name: String)
    case Plus(left: Distance, right: Distance)
    def concrete: Option[Int] = this match {
      case Numeral(n) => Some(n)
      case Variable(_) => None
      case Plus(a, b) => for x <- a.concrete; y <- b.concrete yield x + y
    }
  }
  case class Binding(name: String, constraint: Constraint, distance: Distance)
  case class Obligation(name: String, constraint: Constraint) {
    def binding: Binding = Binding(name, constraint, Distance.Variable(s"distance($name)"))
  }
  enum Witness {
    case Refl(region: String)
    case Use(binding: Binding)
    case Compose(first: Witness, second: Witness)
    def constraint: Constraint = this match {
      case Refl(r) => Constraint(r, r)
      case Use(b) => b.constraint
      case Compose(a, b) =>
        if a.constraint.to != b.constraint.from then throw Diagnostic("Ill-composed region evidence")
        Constraint(a.constraint.from, b.constraint.to)
    }
    def distance: Distance = this match {
      case Refl(_) => Distance.Numeral(0)
      case Use(b) => b.distance
      case Compose(a, b) => (a.distance, b.distance) match {
        case (Distance.Numeral(0), d) => d
        case (d, Distance.Numeral(0)) => d
        case (Distance.Numeral(a), Distance.Numeral(b)) => Distance.Numeral(a + b)
        case (a, b) => Distance.Plus(a, b)
      }
    }
    def substitute(replacements: Map[String, Witness]): Witness = this match {
      case Use(b) => replacements.getOrElse(b.name, this)
      case Compose(a, b) => compose(a.substitute(replacements), b.substitute(replacements))
      case _ => this
    }
    def free: Set[String] = this match {
      case Refl(_) => Set.empty
      case Use(b) => Set(b.name)
      case Compose(a, b) => a.free ++ b.free
    }
  }
  def compose(a: Witness, b: Witness): Witness = {
    if a.constraint.to != b.constraint.from then throw Diagnostic("Ill-composed region evidence")
    (a, b) match {
      case (_: Witness.Refl, _) => b
      case (_, _: Witness.Refl) => a
      case _ => Witness.Compose(a, b)
    }
  }
  case class Reconstruction(witness: Witness, residuals: List[Obligation])

  /** A_TAbs captures precisely the constraints mentioning its region binder. */
  def generalize(region: String, residuals: List[Obligation]): (List[Obligation], List[Obligation]) =
    residuals.partition(_.constraint.free(region))

  /** A_TApp supplies one distance/evidence pair per captured constraint, in
   * the same order as the quantifier's telescope. Remaining obligations may
   * be captured by an enclosing abstraction; they are never silently dropped.
   */
  def instantiate(region: String, actual: String, obligations: List[Obligation],
                  delta: List[Binding], fresh: String => String): List[Reconstruction] =
    obligations.map { o =>
      val c = o.constraint.substitute(region, actual)
      reconstruct(c.from, c.to, delta, fresh)
    }

  def reconstruct(from: String, to: String, delta: List[Binding], fresh: String => String): Reconstruction = {
    def loop(r: String, visited: Set[String]): Reconstruction = {
      if r == to then Reconstruction(Witness.Refl(r), Nil) // A_Refl
      else {
        if visited(r) then throw Diagnostic("Cyclic subregion constraint store")
        delta.filter(_.constraint.from == r) match {
          case Nil => // A_Defer: absence of an outgoing edge, not failure of path search.
            val obligation = Obligation(fresh("evidence"), Constraint(r, to))
            Reconstruction(Witness.Use(obligation.binding), List(obligation))
          case edge :: Nil => // A_Step
            val rest = loop(edge.constraint.to, visited + r)
            Reconstruction(compose(Witness.Use(edge), rest.witness), rest.residuals)
          case _ => throw Diagnostic("A region has more than one outgoing evidence edge")
        }
      }
    }
    loop(from, Set.empty)
  }

  def known(from: String, to: String, delta: List[Binding]): Option[Witness] = {
    val result = reconstruct(from, to, delta, _ => "probe")
    Option.when(result.residuals.isEmpty)(result.witness)
  }

  /** A_MinSubEmpty/One/Keep/Replace. Unrelated regions have no inferred meet. */
  def minimum(targets: List[String], delta: List[Binding]): String = targets match {
    case Nil => Top
    case first :: rest => rest.foldLeft(first) { (a, b) =>
      if known(a, b, delta).nonEmpty then a
      else if known(b, a, delta).nonEmpty then b
      else throw Diagnostic(s"A_AbsSyn: no minimum captured region for $a and $b")
    }
  }

  enum AnswerContext {
    case Hole
    case Layer(value: Type, initial: Computation, rest: AnswerContext, returned: Option[String])
    def plug(answer: Computation): Computation = this match {
      case Hole => answer
      case Layer(v, i, rest, returned) => Computation(v, Effect.Change(i, rest.plug(answer)), returned)
    }
  }
  /** Section 3.2: atc(Γ, CF, C2, d). No ATC inference at symbolic distance. */
  def atc(answer: Computation, operationFinal: Computation, distance: Distance,
          subtype: (Computation, Computation) => Boolean): AnswerContext = {
    val depth = distance.concrete.getOrElse(throw Diagnostic(
      "A_Do: ATC inference requires a concrete evidence distance; an enclosing handler used inside a function has symbolic distance", DiagnosticKind.Unsupported))
    def loop(current: Computation, n: Int): AnswerContext = {
      if n == 0 then {
        if !subtype(operationFinal, current) then throw Diagnostic(
          s"A_Do: operation requires answer ${show(operationFinal)}, found ${show(current)}")
        AnswerContext.Hole
      } else current.effect match {
        case Effect.Change(initial, result) => AnswerContext.Layer(current.value, initial, loop(result, n - 1), current.returned)
        case _ => throw Diagnostic(s"A_Do: evidence crosses $depth delimiters but the answer type has too few concrete layers")
      }
    }
    loop(answer, depth)
  }

  case class FunctionAbstraction(region: String, call: Binding)
}

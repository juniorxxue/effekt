package effekt.atm

import Syntax.*
import Evidence.{Distance, Witness, Binding, Constraint, AnswerContext}

/** Explicit typelike annotations. Source names are resolved before kind checking;
 * evidence arguments are built only from in-scope witnesses, never from a depth.
 */
object Annotations {
  enum Kind {
    case Region, Distance, Computation
    case Evidence(from: String, distance: effekt.atm.Evidence.Distance, to: String)
    case Context(distance: effekt.atm.Evidence.Distance)
  }
  enum Proof {
    case Refl(region: String)
    case Step(handler: String)
    case Variable(name: String)
    case Compose(first: Proof, second: Proof)
    case Path(target: String)
    case Checked(witness: Witness)
  }
  enum Argument {
    case Region(value: String)
    case Distance(value: effekt.atm.Evidence.Distance)
    case Evidence(value: Proof)
    case Context(value: AnswerContext)
    case Computation(value: Syntax.Computation)
  }

  def abstractComputation(form: ComputationForm): Computation =
    Computation(Type.AbstractResult(form), Effect.Abstract, form = Some(form))
  def contextCompose(a: AnswerContext, b: AnswerContext): AnswerContext = a match {
    case AnswerContext.Hole => b
    case AnswerContext.Layer(v, i, rest, n) => AnswerContext.Layer(v, i, contextCompose(rest, b), n)
    case _ if b == AnswerContext.Hole => a
    case AnswerContext.Compose(x, y) => contextCompose(x, contextCompose(y, b))
    case _ => AnswerContext.Compose(a, b)
  }
  def show(c: ComputationForm): String = c match {
    case ComputationForm.Variable(n) => s"cty $n"
    case ComputationForm.Context(k, c) => s"${showContext(k)}[${Syntax.show(c)}]"
  }
  def showContext(c: AnswerContext): String = c match {
    case AnswerContext.Hole => "[]"
    case AnswerContext.Variable(n) => n
    case AnswerContext.Layer(v, i, rest, n) =>
      s"${n.fold("")(_ + ": ")}${Syntax.show(v)} / (${Syntax.show(i)} => ${showContext(rest)})"
    case AnswerContext.Compose(a, b) => s"${showContext(a)}[${showContext(b)}]"
  }

  /** One traversal for substitutions and annotation-preserving type transforms.
   * Resolved binders have distinct identities, including logical binders.
   */
  class Mapper {
    def region(n: String): String = n
    def expression(p: Logic.Expr): Logic.Expr = p
    def after(t: Type): Type = t
    def distance(d: Distance): Distance = d match {
      case Distance.Plus(a, b) => Distance.Plus(distance(a), distance(b))
      case _ => d
    }
    def kind(k: Kind): Kind = k match {
      case Kind.Evidence(a, d, b) => Kind.Evidence(region(a), distance(d), region(b))
      case Kind.Context(d) => Kind.Context(distance(d))
      case _ => k
    }
    def context(c: AnswerContext): AnswerContext = c match {
      case AnswerContext.Layer(v, i, rest, n) => AnswerContext.Layer(typ(v), computation(i), context(rest), n)
      case AnswerContext.Compose(a, b) => contextCompose(context(a), context(b))
      case _ => c
    }
    def computation(c: Computation): Computation = c.form match {
      case Some(ComputationForm.Context(k, a)) => context(k).plug(computation(a))
      case Some(f) => abstractComputation(f)
      case None => Computation(typ(c.value), effect(c.effect), c.returned)
    }
    def effect(e: Effect): Effect = e match {
      case Effect.Change(a, b) => Effect.Change(computation(a), computation(b))
      case Effect.Sequence(a, b) => Effect.Sequence(effect(a), effect(b))
      case _ => e
    }
    def bound(b: EffectBound): EffectBound = b match {
      case EffectBound.After(e) => EffectBound.After(effect(e))
      case _ => b
    }
    def binding(b: Binding): Binding = b.copy(constraint = Constraint(region(b.constraint.from), region(b.constraint.to)), distance = distance(b.distance))
    def witness(w: Witness): Witness = w match {
      case Witness.Refl(r) => Witness.Refl(region(r))
      case Witness.Use(b) => Witness.Use(binding(b))
      case Witness.Compose(a, b) => effekt.atm.Evidence.compose(witness(a), witness(b))
    }
    def signature(o: Signature): Signature = o.copy(argument = typ(o.argument), result = typ(o.result),
      initialAnswer = computation(o.initialAnswer), finalAnswer = computation(o.finalAnswer))
    def typ(t: Type): Type = after(t match {
      case Type.Pair(a, b) => Type.Pair(typ(a), typ(b))
      case Type.Refined(n, b, p) => Type.Refined(n, typ(b), expression(p))
      case Type.Function(a, r, cs, scope, n) => Type.Function(typ(a), computation(r), cs.map(region), scope.map(region), n)
      case Type.LocalFunction(a, r, cs, scope, n) => Type.LocalFunction(typ(a), computation(r), cs.map(region), scope.map(region), n)
      case Type.Continuation(a, r, scope, cs, n) => Type.Continuation(typ(a), computation(r), region(scope), cs.map(region), n)
      case Type.TypePoly(n, b) => Type.TypePoly(n, typ(b))
      case Type.PredicatePoly(n, args, b) => Type.PredicatePoly(n, args, typ(b))
      case Type.EffectPoly(n, b, domain) => Type.EffectPoly(n, typ(b), bound(domain))
      case Type.KindPoly(n, k, b) => Type.KindPoly(n, kind(k), typ(b))
      case Type.Capability(n) => Type.Capability(region(n))
      case Type.CapabilityPoly(n, ops, b, cs, scope, obligations) =>
        Type.CapabilityPoly(n, ops.map(signature), typ(b), cs.map(region), scope.map(region),
          obligations.map(o => o.copy(constraint = Constraint(region(o.constraint.from), region(o.constraint.to)))))
      case Type.AbstractResult(f) => computation(abstractComputation(f)).value
      case other => other
    })
  }
  def substitution(name: String, actual: Argument): Mapper = new Mapper {
    override def region(n: String): String = actual match {
      case Argument.Region(r) if n == name => r
      case _ => n
    }
    override def distance(d: Distance): Distance = (d, actual) match {
      case (Distance.Variable(n), Argument.Distance(value)) if n == name => value
      case _ => super.distance(d)
    }
    override def context(c: AnswerContext): AnswerContext = (c, actual) match {
      case (AnswerContext.Variable(n), Argument.Context(value)) if n == name => value
      case _ => super.context(c)
    }
    override def computation(c: Computation): Computation = (c.form, actual) match {
      case (Some(ComputationForm.Variable(n)), Argument.Computation(value)) if n == name => value
      case _ => super.computation(c)
    }
    override def witness(w: Witness): Witness = (w, actual) match {
      case (Witness.Use(b), Argument.Evidence(Proof.Checked(value))) if b.name == name => value
      case _ => super.witness(w)
    }
  }
  def variable(name: String, kind: Kind): Argument = kind match {
    case Kind.Region => Argument.Region(name)
    case Kind.Distance => Argument.Distance(Distance.Variable(name))
    case Kind.Computation => Argument.Computation(abstractComputation(ComputationForm.Variable(name)))
    case Kind.Context(_) => Argument.Context(AnswerContext.Variable(name))
    case Kind.Evidence(a, d, b) => Argument.Evidence(Proof.Checked(Witness.Use(Binding(name, Constraint(a, b), d))))
  }
  def sameKind(a: Kind, b: Kind): Boolean = (a, b) match {
    case (Kind.Context(x), Kind.Context(y)) => effekt.atm.Evidence.sameDistance(x, y)
    case (Kind.Evidence(a, d, b), Kind.Evidence(c, e, f)) => a == c && b == f && effekt.atm.Evidence.sameDistance(d, e)
    case _ => a == b
  }
  def depth(c: AnswerContext, kinds: Map[String, Kind]): Distance = c match {
    case AnswerContext.Hole => Distance.Numeral(0)
    case AnswerContext.Layer(_, _, rest, _) => Distance.Plus(Distance.Numeral(1), depth(rest, kinds))
    case AnswerContext.Compose(a, b) => Distance.Plus(depth(a, kinds), depth(b, kinds))
    case AnswerContext.Variable(n) => kinds.get(n) match {
      case Some(Kind.Context(d)) => d
      case _ => throw Diagnostic(s"Unknown or non-ATC variable $n")
    }
  }
  def kindOf(a: Argument, kinds: Map[String, Kind]): Kind = a match {
    case Argument.Region(_) => Kind.Region
    case Argument.Distance(_) => Kind.Distance
    case Argument.Computation(_) => Kind.Computation
    case Argument.Context(c) => Kind.Context(depth(c, kinds))
    case Argument.Evidence(Proof.Checked(w)) => Kind.Evidence(w.constraint.from, w.distance, w.constraint.to)
    case _ => throw Diagnostic("Unresolved evidence annotation")
  }
  /** Validate scoped annotation certificates again at the Core boundary. */
  private def validator(kinds: Map[String, Kind], regions: Set[String]): Mapper = new Mapper {
    var ks = kinds
    var rs = regions
    override def region(n: String): String = {
      if n != effekt.atm.Evidence.Top && !rs(n) then throw Diagnostic("Unbound annotation region " + n)
      n
    }
    override def distance(d: Distance): Distance = d match {
      case Distance.Variable(n) if !ks.get(n).contains(Kind.Distance) => throw Diagnostic("Unbound distance variable " + n)
      case Distance.Numeral(n) if n < 0 => throw Diagnostic("Negative distance annotation")
      case _ => super.distance(d)
    }
    override def context(c: AnswerContext): AnswerContext = c match {
      case AnswerContext.Variable(n) if !ks.get(n).exists(_.isInstanceOf[Kind.Context]) => throw Diagnostic("Unbound ATC variable " + n)
      case _ => super.context(c)
    }
    override def computation(c: Computation): Computation = c.form match {
      case Some(ComputationForm.Variable(n)) if !ks.get(n).contains(Kind.Computation) => throw Diagnostic("Unbound computation variable " + n)
      case _ => super.computation(c)
    }
    override def typ(t: Type): Type = t match {
      case Type.KindPoly(n, k, b) =>
        kind(k)
        val savedKinds = ks; val savedRegions = rs
        ks = ks.updated(n, k); if k == Kind.Region then rs += n
        try { typ(b); t } finally { ks = savedKinds; rs = savedRegions }
      case Type.CapabilityPoly(n, ops, b, _, scope, obligations) =>
        val saved = rs; rs += n
        try { ops.foreach(signature); typ(b); obligations.foreach(o => { region(o.constraint.from); region(o.constraint.to) }); t }
        finally { rs = saved; scope.foreach(region) }
      case _ => super.typ(t)
    }
  }
  def validateKind(k: Kind, kinds: Map[String, Kind], regions: Set[String]): Unit = { validator(kinds, regions).kind(k); () }
  def validateArgument(a: Argument, kinds: Map[String, Kind], regions: Set[String]): Unit = {
    val m = validator(kinds, regions)
    a match {
      case Argument.Region(r) => m.region(r)
      case Argument.Distance(d) => m.distance(d)
      case Argument.Context(k) => m.context(k)
      case Argument.Computation(c) => m.computation(c)
      case Argument.Evidence(Proof.Checked(w)) => m.witness(w)
      case _ => throw Diagnostic("Unresolved evidence annotation")
    }
    ()
  }
  def regions(c: Computation): Set[String] = {
    var result = Set.empty[String]
    val m = new Mapper {
      override def region(n: String): String = { if n != effekt.atm.Evidence.Top then result += n; n }
      override def typ(t: Type): Type = t match {
        case Type.KindPoly(n, k, b) =>
          val before = result; result = Set.empty; kind(k); typ(b)
          result = before ++ (if k == Kind.Region then result - n else result); t
        case Type.Function(a, r, _, scope, _) => typ(a); computation(r); scope.foreach(region); t
        case Type.LocalFunction(a, r, _, scope, _) => typ(a); computation(r); scope.foreach(region); t
        case Type.Continuation(a, r, scope, _, _) => typ(a); computation(r); region(scope); t
        case Type.CapabilityPoly(n, ops, b, _, scope, obligations) =>
          val before = result; result = Set.empty; ops.foreach(signature); typ(b)
          obligations.foreach(o => { region(o.constraint.from); region(o.constraint.to) })
          result = before ++ (result - n); scope.foreach(region); t
        case _ => super.typ(t)
      }
    }
    m.computation(c); result
  }
}

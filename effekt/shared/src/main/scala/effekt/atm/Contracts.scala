package effekt.atm

import Syntax.*
import Logic.Expr

/** Traversals shared by dependent, type, predicate, effect and capability
 * substitution. Every checked binder is generative before substitution.
 */
object Contracts {
  private def mapper(f: Type => Type, expr: Expr => Expr): Annotations.Mapper = new Annotations.Mapper {
    override def after(t: Type): Type = f(t)
    override def expression(p: Expr): Expr = expr(p)
  }
  def map(t: Type)(f: Type => Type, expression: Expr => Expr = identity): Type = mapper(f, expression).typ(t)
  def map(c: Computation)(f: Type => Type, expression: Expr => Expr): Computation = mapper(f, expression).computation(c)
  def map(e: Effect)(f: Type => Type, expression: Expr => Expr): Effect = mapper(f, expression).effect(e)
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
    case Type.Function(a, r, cs, scope, _, caller) => Type.Function(a, r, cs, scope, caller = caller)
    case Type.FixedFunction(a, r, cs, scope, _) => Type.FixedFunction(a, r, cs, scope)
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
    case Type.Function(a, r, _, _, name, _) => free(a) ++ (free(r) -- name)
    case Type.FixedFunction(a, r, _, _, name) => free(a) ++ (free(r) -- name)
    case Type.Continuation(a, r, _, _, name) => free(a) ++ (free(r) -- name)
    case Type.AbstractResult(f) => free(Annotations.abstractComputation(f))
    case Type.KindPoly(_, _, b) => free(b)
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
  def free(c: Computation): Set[String] = c.form match {
    case Some(ComputationForm.Variable(_)) => Set.empty
    case Some(ComputationForm.Context(k, a)) => freeContext(k) ++ free(a)
    case None => free(c.value) ++ (c.effect match {
    case Effect.Change(initial, result) => (free(initial) -- c.returned) ++ free(result)
    case other => free(other)
    })
  }
  private def freeContext(c: Evidence.AnswerContext): Set[String] = c match {
    case Evidence.AnswerContext.Layer(v, i, r, n) => free(v) ++ (free(i) -- n) ++ freeContext(r)
    case Evidence.AnswerContext.Compose(a, b) => freeContext(a) ++ freeContext(b)
    case _ => Set.empty
  }
  def free(e: Effect): Set[String] = e match {
    case Effect.Change(a, b) => free(a) ++ free(b)
    case Effect.Sequence(a, b) => free(a) ++ free(b)
    case _ => Set.empty
  }

  def usesRefinements(p: Program): Boolean = {
    var found = p.measures.nonEmpty
    def inspect(t: Type): Type = {
      t match {
        case _: Type.Refined | _: Type.PredicatePoly => found = true
        case Type.Function(_, _, _, _, Some(_), _) | Type.FixedFunction(_, _, _, _, Some(_)) => found = true
        case _ => ()
      }
      t
    }
    def ty(t: Type): Unit = { map(t)(inspect); () }
    def comp(c: Computation): Unit = { map(c)(inspect, identity); () }
    def effect(e: Effect): Unit = e match {
      case Effect.Change(a, b) => comp(a); comp(b)
      case Effect.Sequence(a, b) => effect(a); effect(b)
      case _ => ()
    }
    def value(v: Value): Unit = v match {
      case Value.Lambda(_, a, r, b, _) => ty(a); comp(r); term(b)
      case Value.ContextLambda(_, a, r, _, b, _) => ty(a); comp(r); term(b)
      case Value.FixedLambda(_, a, r, b, _) => a.foreach(ty); r.foreach(comp); term(b)
      case Value.InferredLambda(_, a, b, _) => ty(a); term(b)
      case Value.UnannotatedLambda(_, r, b, _) => r.foreach(comp); term(b)
      case Value.CapabilityLambda(_, ops, b, _) => ops.foreach(signature); value(b)
      case Value.Instantiate(f, c) => value(f); value(c)
      case Value.EffectLambda(_, b, bound) =>
        bound match { case EffectBound.After(e) => effect(e); case _ => () }
        value(b)
      case Value.InstantiateEffect(f, e) => value(f); effect(e)
      case Value.KindLambda(_, _, b) => value(b)
      case Value.InstantiateKind(f, a) => value(f); annotation(a)
      case Value.InstantiatePolymorphic(f, alternatives) =>
        value(f)
        alternatives.foreach {
          case PolymorphicArgument.ValueType(t) => ty(t)
          case PolymorphicArgument.Kind(a) => annotation(a)
          case PolymorphicArgument.AnswerEffect(e) => effect(e)
          case _: PolymorphicArgument.Predicate => found = true
        }
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
    def annotation(a: Annotations.Argument): Unit = a match {
      case Annotations.Argument.Computation(c) => comp(c)
      case Annotations.Argument.Context(c) => comp(c.plug(Computation(Type.Unit)))
      case _ => ()
    }
    def signature(o: Signature): Unit = { if o.returned.nonEmpty then found = true; ty(o.argument); ty(o.result); comp(o.initialAnswer); comp(o.finalAnswer) }
    def operation(o: Operation): Unit = {
      if o.returned.nonEmpty then found = true
      ty(o.argument); ty(o.result); o.interfaceArguments.foreach(ty)
      o.argumentAnnotations.flatten.foreach(ty)
      o.answers.foreach { (initial, result) => comp(initial); comp(result) }
      term(o.body)
    }
    def term(t: Term): Unit = t match {
      case Term.AnnotationAlias(_, a, b) => annotation(a); term(b)
      case Term.AnnotatedPerform(_, _, a, _, c) => value(a); c.foreach(k => annotation(Annotations.Argument.Context(k)))
      case Term.SingleAnnotatedPerform(_, _, a, _) => value(a)
      case Term.TypeAlias(_, definition, body) => ty(definition); term(body)
      case Term.Return(v) => value(v)
      case Term.Assert(v) => found = true; value(v)
      case Term.Ascribe(b, c) => comp(c); term(b)
      case Term.ExplicitReturn(b) => term(b)
      case Term.LetRec(_, _, a, r, d, b, _, _) => ty(a); comp(r); term(d); term(b)
      case Term.Let(_, a, b) => term(a); term(b)
      case Term.Apply(f, a) => value(f); value(a)
      case Term.If(c, a, b) => value(c); term(a); term(b)
      case Term.Match(v, cases) => found = true; value(v); cases.foreach(c => term(c.body))
      case Term.Perform(_, _, a) => value(a)
      case Term.Handle(_, r, ops, b, _, ret) => comp(r); ops.foreach(operation); term(b); term(ret)
      case Term.InferredHandle(_, ops, b, _, ret) => ops.foreach(operation); term(b); term(ret)
    }
    p.datatypes.foreach(_.constructors.foreach(_.fields.foreach(f => ty(f._2))))
    p.typeAliases.foreach(a => ty(a._2))
    p.interfaces.foreach(_.operations.foreach { o =>
      ty(o.argument); ty(o.result); if o.returned.nonEmpty then found = true
    })
    comp(p.result); term(p.body); found
  }
}

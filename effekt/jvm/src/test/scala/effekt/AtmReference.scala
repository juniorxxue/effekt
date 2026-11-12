package effekt

import effekt.atm.Syntax

/** A test oracle for the source's deep lexical handlers. This interpreter reads
 * the untyped source AST, not checker annotations or generated answer tags.
 * It is intentionally independent of the answer-packaging translation.
 */
object AtmReference {
  enum Value {
    case Integer(value: BigInt)
    case Boolean(value: scala.Boolean)
    case Unit
    case Token(name: String)
    case Data(name: String, arguments: List[Value])
    case Pair(first: Value, second: Value)
    case Capability(identity: Object)
    case Function(call: Value => Computation)
    case CapabilityAbstraction(instantiate: Value => Value)
    case EffectAbstraction(body: Value)
  }
  enum Computation {
    case Returned(value: Value)
    case Requested(capability: Object, operation: String, argument: Value, resume: Value => Computation)
  }
  private case class Environment(values: Map[String, Value] = Map.empty,
                                 capabilities: Map[String, Object] = Map.empty) {
    def bind(name: String, value: Value): Environment = copy(values = values.updated(name, value))
    def withCapability(name: String, identity: Object): Environment =
      copy(capabilities = capabilities.updated(name, identity), values = values - name)
  }
  private def bind(computation: Computation)(next: Value => Computation): Computation = computation match {
    case Computation.Returned(v) => next(v)
    case Computation.Requested(cap, op, arg, resume) =>
      Computation.Requested(cap, op, arg, v => bind(resume(v))(next))
  }
  private def value(source: Syntax.Value, env: Environment): Value = source match {
    case Syntax.Value.Integer(n) => Value.Integer(n)
    case Syntax.Value.Boolean(b) => Value.Boolean(b)
    case Syntax.Value.Unit => Value.Unit
    case Syntax.Value.Token(name) => Value.Token(name)
    case Syntax.Value.Constructor(n, args) => Value.Data(n, args.map(value(_, env)))
    case Syntax.Value.Capability(name) => Value.Capability(env.capabilities(name))
    case Syntax.Value.Variable(name) => env.values.getOrElse(name, Value.Capability(env.capabilities(name)))
    case Syntax.Value.FixedLambda(parameter, _, _, body, _) => Value.Function(arg => term(body, env.bind(parameter, arg)))
    case Syntax.Value.Lambda(parameter, _, _, body, _) => Value.Function(arg => term(body, env.bind(parameter, arg)))
    case Syntax.Value.ContextLambda(parameter, _, _, contexts, body, _) =>
      contexts.foldRight[Value](Value.Function(arg => term(body, env.bind(parameter, arg))))((_, v) => Value.EffectAbstraction(v))
    case Syntax.Value.InferredLambda(parameter, _, body, _) => Value.Function(arg => term(body, env.bind(parameter, arg)))
    case Syntax.Value.UnannotatedLambda(parameter, _, body, _) => Value.Function(arg => term(body, env.bind(parameter, arg)))
    case Syntax.Value.CapabilityLambda(parameter, _, body, _) => Value.CapabilityAbstraction {
      case Value.Capability(identity) => value(body, env.withCapability(parameter, identity))
      case other => throw new IllegalStateException(s"Cannot instantiate with $other")
    }
    case Syntax.Value.Instantiate(function, capability) => value(function, env) match {
      case Value.CapabilityAbstraction(instantiate) => instantiate(value(capability, env))
      case other => throw new IllegalStateException(s"Cannot instantiate $other")
    }
    case Syntax.Value.KindLambda(_, _, body) => Value.EffectAbstraction(value(body, env))
    case Syntax.Value.InstantiatePolymorphic(function, _) => value(function, env) match {
      case Value.EffectAbstraction(body) => body
      case other => throw new IllegalStateException(s"Cannot instantiate a polymorphic argument in $other")
    }
    case Syntax.Value.InstantiateKind(function, _) => value(function, env) match {
      case Value.EffectAbstraction(body) => body
      case _ => throw new IllegalArgumentException("Expected a kind abstraction")
    }
    case Syntax.Value.TypeLambda(_, body) => Value.EffectAbstraction(value(body, env))
    case Syntax.Value.PredicateLambda(_, _, body) => Value.EffectAbstraction(value(body, env))
    case Syntax.Value.EffectLambda(_, body, _) => Value.EffectAbstraction(value(body, env))
    case Syntax.Value.InstantiateType(function, _) => value(function, env) match {
      case Value.EffectAbstraction(body) => body
      case other => throw new IllegalStateException(s"Cannot instantiate a type in $other")
    }
    case Syntax.Value.InstantiatePredicate(function, _, _) => value(function, env) match {
      case Value.EffectAbstraction(body) => body
      case other => throw new IllegalStateException(s"Cannot instantiate a predicate in $other")
    }
    case Syntax.Value.InstantiateEffect(function, _) => value(function, env) match {
      case Value.EffectAbstraction(body) => body
      case other => throw new IllegalStateException(s"Cannot instantiate an effect in $other")
    }
    case Syntax.Value.Primitive(name, arguments) => (name, arguments.map(value(_, env))) match {
      case ("pair", List(a, b)) => Value.Pair(a, b)
      case ("fst", List(Value.Pair(a, _))) => a
      case ("snd", List(Value.Pair(_, b))) => b
      case ("zero", List(Value.Integer(n))) => Value.Boolean(n == 0)
      case ("add", List(Value.Integer(a), Value.Integer(b))) => Value.Integer(a + b)
      case ("not", List(Value.Boolean(b))) => Value.Boolean(!b)
      case ("sub", List(Value.Integer(a), Value.Integer(b))) => Value.Integer(a - b)
      case ("mul", List(Value.Integer(a), Value.Integer(b))) => Value.Integer(a * b)
      case ("div", List(Value.Integer(a), Value.Integer(b))) => Value.Integer(a / b)
      case ("mod", List(Value.Integer(a), Value.Integer(b))) => Value.Integer(a % b)
      case ("lt", List(Value.Integer(a), Value.Integer(b))) => Value.Boolean(a < b)
      case ("le", List(Value.Integer(a), Value.Integer(b))) => Value.Boolean(a <= b)
      case ("gt", List(Value.Integer(a), Value.Integer(b))) => Value.Boolean(a > b)
      case ("ge", List(Value.Integer(a), Value.Integer(b))) => Value.Boolean(a >= b)
      case ("eq", List(a, b)) => Value.Boolean(a == b)
      case ("ne", List(a, b)) => Value.Boolean(a != b)
      case ("and", List(Value.Boolean(a), Value.Boolean(b))) => Value.Boolean(a && b)
      case ("or", List(Value.Boolean(a), Value.Boolean(b))) => Value.Boolean(a || b)
      case other => throw new IllegalStateException(s"Stuck primitive: $other")
    }
  }
  private def term(source: Syntax.Term, env: Environment): Computation = source match {
    case Syntax.Term.Return(v) => Computation.Returned(value(v, env))
    case Syntax.Term.TypeAlias(_, _, b) => term(b, env)
    case Syntax.Term.AnnotationAlias(_, _, b) => term(b, env)
    case Syntax.Term.Ascribe(b, _) => term(b, env)
    case Syntax.Term.Assert(v) =>
      if value(v, env) != Value.Boolean(true) then throw new IllegalStateException("Assertion failed")
      Computation.Returned(Value.Unit)
    case Syntax.Term.ExplicitReturn(body) => term(body, env)
    case Syntax.Term.LetRec(name, parameter, _, _, definition, body, _, _) =>
      lazy val function: Value = Value.Function(arg => term(definition, env.bind(name, function).bind(parameter, arg)))
      term(body, env.bind(name, function))
    case Syntax.Term.Let(name, first, rest) => bind(term(first, env))(v => term(rest, env.bind(name, v)))
    case Syntax.Term.Apply(function, argument) => value(function, env) match {
      case Value.Function(call) => call(value(argument, env))
      case other => throw new IllegalStateException(s"Cannot call $other")
    }
    case Syntax.Term.If(condition, whenTrue, whenFalse) => value(condition, env) match {
      case Value.Boolean(b) => term(if b then whenTrue else whenFalse, env)
      case other => throw new IllegalStateException(s"Non-boolean condition $other")
    }
    case Syntax.Term.Match(scrutinee, cases) => value(scrutinee, env) match {
      case Value.Data(n, fields) =>
        val c = cases.find(_.constructor == n).getOrElse(throw new IllegalStateException("Non-exhaustive source match"))
        term(c.body, c.parameters.zip(fields).foldLeft(env) { case (e, (p, v)) => e.bind(p, v) })
      case _ => throw new IllegalStateException("Source match requires a constructor")
    }
    case Syntax.Term.AnnotatedPerform(c, op, arg, _, _) => term(Syntax.Term.Perform(c, op, arg), env)
    case Syntax.Term.SingleAnnotatedPerform(c, op, arg, _) => term(Syntax.Term.Perform(c, op, arg), env)
    case Syntax.Term.Perform(receiver, operation, argument) =>
      val identity = env.values.get(receiver) match {
        case Some(Value.Capability(id)) => id
        case None => env.capabilities(receiver)
        case Some(other) => throw new IllegalStateException(s"Non-capability receiver $other")
      }
      Computation.Requested(identity, operation, value(argument, env), Computation.Returned.apply)
    case Syntax.Term.InferredHandle(cap, operations, body, returned, onReturn) =>
      term(Syntax.Term.Handle(cap, Syntax.Computation(Syntax.Type.Unit), operations, body, returned, onReturn), env)
    case Syntax.Term.Handle(cap, _, operations, body, returned, onReturn) =>
      val identity = new Object()
      def handle(computation: Computation): Computation = computation match {
        case Computation.Returned(v) => term(onReturn, env.bind(returned, v))
        case Computation.Requested(id, name, argument, resume) if id eq identity =>
          val op = operations.find(_.name == name).get
          val k = Value.Function(v => handle(resume(v)))
          term(op.body, env.bind(op.parameter, argument).bind(op.continuation, k))
        case Computation.Requested(id, name, argument, resume) =>
          Computation.Requested(id, name, argument, v => handle(resume(v)))
      }
      handle(term(body, env.withCapability(cap, identity)))
  }

  def run(program: Syntax.Program): String = term(program.body, Environment()) match {
    case Computation.Returned(Value.Integer(n)) => n.toString
    case Computation.Returned(Value.Boolean(b)) => b.toString
    case Computation.Returned(Value.Unit) => "unit"
    case Computation.Returned(_: Value.Pair) => "<pair>"
    case Computation.Returned(Value.Token(name)) => name
    case Computation.Returned(_: Value.Data) => "<data>"
    case Computation.Returned(_: Value.Function | _: Value.CapabilityAbstraction | _: Value.EffectAbstraction) => "<function>"
    case Computation.Returned(_: Value.Capability) => "<capability>"
    case Computation.Requested(_, name, _, _) => throw new IllegalStateException(s"Unhandled operation $name")
  }
}

package effekt.atm

import Syntax.*
import Evidence.*

/** Bidirectional algorithmic checking (supplement, Figures 9--15).
 * Checked trees retain explicit evidence applications, abstractions and ATCs
 * for elaboration to the independent region/evidence Core representation.
 */
object Checker {
  case class OperationTyping(signature: Signature, context: AnswerContext, inferred: Computation)
  case class CheckedValue(source: Value, tpe: Type, captures: Set[String], children: List[CheckedValue] = Nil,
                          body: Option[CheckedTerm] = None, symbolic: Option[Logic.Expr] = None,
                          residuals: List[Obligation] = Nil, evidence: List[Witness] = Nil,
                          abstraction: Option[FunctionAbstraction] = None,
                          quantified: List[Binding] = Nil, discharged: Set[String] = Set.empty)
  case class CheckedOperation(source: Operation, body: CheckedTerm)
  case class CheckedTerm(source: Term, tpe: Computation, captures: Set[String],
                         values: List[CheckedValue] = Nil, children: List[CheckedTerm] = Nil,
                         operations: List[CheckedOperation] = Nil,
                         coercedFrom: Option[Type] = None, operationArgument: Option[Type] = None,
                         logic: Logic.Context = Logic.Context(), symbolic: Option[Logic.Expr] = None,
                         residuals: List[Obligation] = Nil, evidence: List[Witness] = Nil,
                         handlerEvidence: Option[Binding] = None,
                         operationTyping: Option[OperationTyping] = None,
                         patternBindings: List[List[(String, Type)]] = Nil)
  final class CheckedProgram private[Checker](val source: Program, val body: CheckedTerm, val datatypes: List[DataDeclaration]) {
    def elaborated: String = Elaboration.show(body)
  }

  def check(program: Program): Either[Diagnostic, CheckedProgram] = check(program, RefinementSolver.default)
  def check(program: Program, solver: Logic.Solver): Either[Diagnostic, CheckedProgram] = new Session(solver).check(program)

  /** Optional observations for reporting partial inference on rejected sources.
   * A reconstructed witness can carry residuals even when ATC inference fails.
   * These observations neither discharge obligations nor change the result.
   */
  case class OperationInference(receiver: String, operation: String,
                                evidence: Reconstruction, context: Either[Diagnostic, AnswerContext])
  case class Inspection(result: Either[Diagnostic, CheckedProgram], operations: List[OperationInference])
  def inspect(program: Program): Inspection = {
    val observations = scala.collection.mutable.ListBuffer.empty[OperationInference]
    val result = new Session(RefinementSolver.default, Some(o => { observations += o; () })).check(program)
    Inspection(result, observations.toList)
  }

  private class Session(solver: Logic.Solver, observe: Option[OperationInference => Unit] = None) {
    import Logic.Expr
    private var refined = false
    private var datatypes = List.empty[DataDeclaration]
    private var constructors = Map.empty[String, (String, DataConstructor)]
    private val proofCache = scala.collection.mutable.Map.empty[String, Logic.Verdict]
    private def proves(context: Logic.Context, conclusion: Expr): Boolean = {
      if conclusion == Logic.True || context.assumptions.contains(conclusion) || context.assumptions.contains(Logic.False) then true
      else {
        val query = Logic.query(context, conclusion)
        proofCache.getOrElseUpdate(query, solver.prove(context, conclusion)) match {
          case Logic.Verdict.Valid => true
          case Logic.Verdict.Invalid => false
          case Logic.Verdict.Unknown(reason) => throw Diagnostic(s"Refinement verification unavailable: $reason", DiagnosticKind.VerificationUnavailable)
        }
      }
    }
    private def singleton(base: Type, expression: Expr): Type = {
      val name = fresh("value")
      Type.Refined(name, Contracts.base(base), Logic.eq(Expr.Variable(name), expression))
    }
    private def logical(expression: Expr, env: Environment): Expr = expression match {
      case Expr.Variable(name) => Expr.Variable(env.logicalNames.getOrElse(name, fail(s"Unknown refinement variable $name")))
      case Expr.Predicate(name, args) =>
        Expr.Predicate(env.predicateNames.getOrElse(name, fail(s"Unknown refinement predicate $name")), args.map(logical(_, env)))
      case Expr.App(op, args) => Expr.App(op, args.map(logical(_, env)))
      case _: Expr.Exists => fail("Existentials are internal verification formulas")
      case Expr.Forall(name, sort, body) =>
        val id = fresh(name)
        Expr.Forall(id, sort, logical(body, env.bindIdentity(name, Contracts.typeOfSort(sort), id)))
      case other => other
    }
    private def symbol(value: CheckedValue): Expr = value.symbolic.getOrElse(fail("A dependent contract requires a logical base or pair argument"))
    private def symbolicTerm(term: CheckedTerm, env: Environment): CheckedTerm = {
      if !refined then term
      else Contracts.sort(term.tpe.value) match {
        case None => term.copy(logic = env.logic)
        case Some(sort) =>
          // Instantiate a dependent result binder afresh at every call. Reusing
          // the binder stored in a function type would identify distinct results.
          val name = fresh("result")
          val v = Expr.Variable(name)
          val tpe = (term.tpe.returned, term.tpe.effect) match {
            case (Some(old), Effect.Change(i, f)) =>
              term.tpe.copy(effect = Effect.Change(Contracts.substitute(i, old, v), f), returned = Some(name))
            case _ => term.tpe
          }
          term.copy(tpe = tpe, logic = env.logic.bind(name, sort).assume(Contracts.member(tpe.value, v)), symbolic = Some(v))
      }
    }

    private var nextIdentity = 0
    private def fresh(name: String): String = {
      nextIdentity += 1
      s"$name#$nextIdentity"
    }

    private case class Environment(values: Map[String, Type] = Map.empty,
                                   capabilities: Map[String, List[Signature]] = Map.empty,
                                   names: Map[String, String] = Map.empty,
                                   effects: Map[String, String] = Map.empty,
                                   effectBounds: Map[String, EffectBound] = Map.empty,
                                   region: String = Evidence.Top,
                                   delta: List[Binding] = Nil,
                                   types: Map[String, String] = Map.empty,
                                   aliases: Map[String, Type] = Map.empty,
                                   predicateNames: Map[String, String] = Map.empty,
                                   logicalNames: Map[String, String] = Map.empty,
                                   logic: Logic.Context = Logic.Context()) {
      def bind(name: String, tpe: Type, symbolic: Option[Expr] = None): Environment =
        bindIdentity(name, tpe, fresh(name), symbolic)
      def bindIdentity(name: String, tpe: Type, identity: String, symbolic: Option[Expr] = None): Environment = {
        val next = copy(values = values.updated(name, tpe))
        if !refined then next else Contracts.sort(tpe) match {
          case None => next.copy(logicalNames = logicalNames - name)
          case Some(sort) =>
            val v = Expr.Variable(identity)
            val facts = symbolic.fold(Contracts.member(tpe, v))(e => Logic.and(Contracts.member(tpe, v), Logic.eq(v, e)))
            next.copy(logicalNames = logicalNames.updated(name, identity), logic = logic.bind(identity, sort).assume(facts))
        }
      }
      def withType(name: String, identity: String): Environment = copy(types = types.updated(name, identity), aliases = aliases - name)
      def withPredicate(name: String, identity: String, args: List[Logic.Sort]): Environment =
        copy(predicateNames = predicateNames.updated(name, identity), logic = logic.copy(predicates = logic.predicates.updated(identity, args)))
      def withEffect(name: String, identity: String, bound: EffectBound): Environment =
        copy(effects = effects.updated(name, identity), effectBounds = effectBounds.updated(identity, bound))
      def capability(name: String): String = names.getOrElse(name, fail(s"Unknown or out-of-scope capability $name"))
      def localRegion(name: String): String = names.getOrElse(name, aliases.get(name) match {
        case Some(Type.LocalFunction(_, _, _, Some(r), _)) => r
        case _ => capability(name)
      })
      def withCapability(name: String, identity: String, ops: List[Signature]): Environment =
        copy(capabilities = capabilities.updated(identity, ops), names = names.updated(name, identity))
      def enterHandler(name: String, identity: String, ops: List[Signature], edge: Binding): Environment =
        withCapability(name, identity, ops).copy(region = identity, delta = edge :: delta)
    }

    def check(program: Program): Either[Diagnostic, CheckedProgram] = try {
      if program.result.effect != Effect.Pure then fail("A closed program must have a pure final computation type")
      refined = Contracts.usesRefinements(program)
      datatypes = Datatypes.validate(program.datatypes)
      val logicalData = Datatypes.logical(datatypes)
      val measures = Measures.validate(program.measures, logicalData)
      val env = Environment(logic = Logic.Context(datatypes = logicalData, measures = measures))
      datatypes = datatypes.map(d => d.copy(constructors = d.constructors.map { c =>
        var scope = env
        c.copy(fields = c.fields.map { (name, tpe) =>
          val t = resolve(tpe, scope)
          scope = scope.bindIdentity(name, t, name)
          (name, t)
        })
      }))
      constructors = Datatypes.constructors(datatypes)
      val expected = resolve(program.result, env)
      val checked = checkTerm(program.body, expected, env)
      if free(expected).nonEmpty then fail("The result type contains an escaping capability")
      require(checked.residuals.isEmpty, s"Unsolved region obligations at program boundary: ${checked.residuals}")
      Elaboration.validate(checked)
      Right(new CheckedProgram(program, checked, datatypes))
    } catch { case d: Diagnostic => Left(d) }

    private def fail(message: String): Nothing = throw Diagnostic(message)
    private def require(condition: Boolean, message: => String, kind: DiagnosticKind = DiagnosticKind.TypeError): Unit =
      if !condition then throw Diagnostic(message, kind)

    private def free(tpe: Type): Set[String] = tpe match {
      case Type.Capability(name) => Set(name)
      case Type.Continuation(arg, result, region, _, _) => free(arg) ++ free(result) + region
      case Type.Function(arg, result, _, context, _) => free(arg) ++ free(result) ++ context.toSet.filter(_ != Evidence.Top)
      case Type.LocalFunction(arg, result, _, context, _) => free(arg) ++ free(result) ++ context.toSet.filter(_ != Evidence.Top)
      case Type.CapabilityPoly(name, ops, body, cs, scope, obligations) =>
        (free(body) ++ obligations.flatMap(_.constraint.free) ++ ops.flatMap(free) - name) ++ scope.toSet.filter(_ != Evidence.Top)
      case Type.EffectPoly(_, body, bound) => free(body) ++ free(bound)
      case Type.Refined(_, base, _) => free(base)
      case Type.Pair(a, b) => free(a) ++ free(b)
      case Type.TypePoly(_, body) => free(body)
      case Type.PredicatePoly(_, _, body) => free(body)
      case _ => Set.empty
    }
    private def free(op: Signature): Set[String] =
      free(op.argument) ++ free(op.result) ++ free(op.initialAnswer) ++ free(op.finalAnswer)
    private def free(tpe: Computation): Set[String] = free(tpe.value) ++ free(tpe.effect)
    private def free(effect: Effect): Set[String] = effect match {
      case Effect.Pure | Effect.Variable(_) => Set.empty
      case Effect.Change(a, b) => free(a) ++ free(b)
      case Effect.Sequence(a, b) => free(a) ++ free(b)
    }
    private def free(bound: EffectBound): Set[String] = bound match {
      case EffectBound.After(prefix) => free(prefix)
      case _ => Set.empty
    }
    private def captures(tpe: Type): Set[String] = tpe match {
      case Type.Capability(name) => Set(name)
      case Type.Pair(a, b) => captures(a) ++ captures(b)
      case Type.Function(_, _, cs, _, _)  => cs
      case Type.LocalFunction(_, _, cs, _, _) => cs
      case Type.Continuation(_, _, _, cs, _) => cs
      case Type.CapabilityPoly(_, _, _, cs, _, obligations) => cs
      case Type.EffectPoly(_, body, _) => captures(body)
      case Type.TypePoly(_, body) => captures(body)
      case Type.PredicatePoly(_, _, body) => captures(body)
      case _ => Set.empty
    }
    private def resolve(tpe: Type, env: Environment): Type = tpe match {
      case Type.Pair(a, b) => Type.Pair(resolve(a, env), resolve(b, env))
      case Type.Token(name) if env.types.contains(name) => Type.Variable(env.types(name))
      case Type.Token(name) if env.aliases.contains(name) => env.aliases(name)
      case Type.Token(name) if env.logic.datatypes.contains(name) => Type.Data(name)
      case Type.Data(name) =>
        require(env.logic.datatypes.contains(name), s"Unknown datatype $name"); tpe
      case Type.Variable(name) => Type.Variable(env.types.getOrElse(name, fail(s"Unknown type variable $name")))
      case Type.Refined(parameter, base, predicate) =>
        val b = resolve(base, env)
        val sort = Contracts.sort(b).getOrElse(fail("Refinements require a first-order logical base"))
        val id = fresh(parameter)
        val inner = env.bindIdentity(parameter, b, id)
        val p = logical(predicate, inner)
        require(Logic.sort(p, inner.logic) == Logic.Sort.Bool, "A refinement predicate must be Bool")
        Type.Refined(id, b, p)
      case Type.TypePoly(parameter, body) =>
        val id = fresh(parameter)
        Type.TypePoly(id, resolve(body, env.withType(parameter, id)))
      case Type.PredicatePoly(parameter, args, body) =>
        val id = fresh(parameter)
        Type.PredicatePoly(id, args, resolve(body, env.withPredicate(parameter, id, args)))
      case Type.Capability(name) =>
        Type.Capability(env.names.getOrElse(name, fail(s"Unknown capability type $name")))
      case Type.LocalFunction(arg, result, cs, region, parameter) =>
        val target = region.map(r => if r == Evidence.Top then r else env.localRegion(r)).getOrElse(env.region)
        val scope = env.copy(region = target)
        val argument = resolve(arg, scope)
        val id = parameter.map(fresh)
        val inner = parameter.zip(id).foldLeft(scope) { case (e, (n, i)) => e.bindIdentity(n, argument, i) }
        // Legacy emitter metadata: a local callback may capture any enclosing
        // capability reachable from its exact call region. This is not an
        // additional source subtyping or region condition.
        val reachable = env.capabilities.keySet.filter(c => Evidence.known(target, c, env.delta).nonEmpty)
        Type.LocalFunction(argument, resolve(result, inner), cs.map(env.capability) ++ reachable, Some(target), id)
      case Type.Function(arg, result, cs, _, parameter) =>
        val argument = resolve(arg, env)
        val identity = parameter.map(fresh)
        val inner = parameter.zip(identity).foldLeft(env) { case (e, (name, id)) => e.bindIdentity(name, argument, id) }
        Type.Function(argument, resolve(result, inner), cs.map(env.capability),
          Some(if cs.isEmpty then env.region else Evidence.minimum(cs.toList.map(env.capability), env.delta)), identity)
      case Type.CapabilityPoly(name, operations, body, cs, scope, obligations) =>
        require(scope.isEmpty, "Scoped capability tails are a prototype extension, not part of the paper algorithm", DiagnosticKind.Unsupported)
        val identity = fresh(name)
        val ops = signatures(operations, env.withCapability(name, identity, Nil))
        val inner = env.withCapability(name, identity, ops)
        Type.CapabilityPoly(identity, ops, resolve(body, inner), cs.map(env.capability), scope.map(_ => env.region),
          obligations.map(o => o.copy(constraint = o.constraint.substitute(name, identity))))
      case Type.EffectPoly(name, body, bound) =>
        val identity = fresh(name)
        val domain = resolve(bound, env)
        Type.EffectPoly(identity, resolve(body, env.withEffect(name, identity, domain)), domain)
      case _ => tpe
    }
    private def resolve(tpe: Computation, env: Environment): Computation = {
      val v = resolve(tpe.value, env)
      val binder = tpe.returned.map(fresh)
      val e = (tpe.effect, tpe.returned.zip(binder)) match {
        case (Effect.Change(initial, result), Some((name, id))) =>
          Effect.Change(resolve(initial, env.bindIdentity(name, v, id)), resolve(result, env))
        case _ => resolve(tpe.effect, env)
      }
      Computation(v, e, binder)
    }
    private def resolve(effect: Effect, env: Environment): Effect = effect match {
      case Effect.Pure => Effect.Pure
      case Effect.Change(a, b) => Effect.Change(resolve(a, env), resolve(b, env))
      case Effect.Variable(name) => Effect.Variable(env.effects.getOrElse(name, fail(s"Unknown answer-effect variable $name")))
      case Effect.Sequence(_, _) => fail("Answer-effect sequences are a prototype extension, not part of the paper algorithm")
    }
    private def resolve(bound: EffectBound, env: Environment): EffectBound = bound match {
      case EffectBound.Any => EffectBound.Any
      case _ => fail("Repeatable/after bounds are prototype extensions, not part of the paper algorithm")
    }

    private def signatures(operations: List[Signature], env: Environment = Environment()): List[Signature] = {
      require(operations.nonEmpty, "A capability contract must declare at least one operation")
      require(operations.map(_.name).distinct.size == operations.size, "Duplicate operation in capability contract")
      operations.map(resolveSignature(_, env)).sortBy(_.name)
    }
    private def resolveSignature(op: Signature, env: Environment): Signature = {
      val arg = resolve(op.argument, env)
      val parameter = if refined && Contracts.sort(arg).nonEmpty then op.parameter.map(fresh) else None
      val scope = op.parameter.zip(parameter).foldLeft(env) { case (e, (name, id)) => e.bindIdentity(name, arg, id) }
      val result = resolve(op.result, scope)
      require(op.returned.isEmpty || Contracts.sort(result).nonEmpty,
        "Dependent operation results require an Int, Bool, Unit or logical pair type")
      val returned = op.returned.map(fresh)
      val inner = op.returned.zip(returned).foldLeft(scope) { case (e, (name, id)) => e.bindIdentity(name, result, id) }
      op.copy(argument = arg, result = result, initialAnswer = resolve(op.initialAnswer, inner),
        finalAnswer = resolve(op.finalAnswer, scope), parameter = parameter, returned = returned)
    }
    private def operationArgument(op: Signature, argument: Expr): Signature = op.parameter.fold(op) { parameter =>
      op.copy(result = Contracts.substitute(op.result, parameter, argument),
        initialAnswer = Contracts.substitute(op.initialAnswer, parameter, argument),
        finalAnswer = Contracts.substitute(op.finalAnswer, parameter, argument), parameter = None)
    }

    private def open(tpe: Type, bound: String, actual: String): Type =
      Contracts.map(tpe)(openNode(_, bound, actual))
    private def openNode(tpe: Type, bound: String, actual: String): Type = tpe match {
      case Type.Capability(n) => Type.Capability(if n == bound then actual else n)
      case Type.Function(a, c, cs, region, p) =>
        Type.Function(a, c, cs.map(n => if n == bound then actual else n), region.map(r => if r == bound then actual else r), p)
      case Type.LocalFunction(a, c, cs, region, p) =>
        Type.LocalFunction(a, c, cs.map(n => if n == bound then actual else n), region.map(r => if r == bound then actual else r), p)
      case Type.Continuation(a, c, region, cs, p) =>
        Type.Continuation(a, c, if region == bound then actual else region, cs.map(n => if n == bound then actual else n), p)
      case Type.CapabilityPoly(n, ops, b, cs, scope, obligations) =>
        Type.CapabilityPoly(n, ops, b, cs.map(n => if n == bound then actual else n),
          scope.map(r => if r == bound then actual else r),
          obligations.map(o => o.copy(constraint = o.constraint.substitute(bound, actual))))
      case other => other
    }
    private def open(op: Signature, bound: String, actual: String): Signature =
      op.copy(argument = open(op.argument, bound, actual), result = open(op.result, bound, actual),
        initialAnswer = open(op.initialAnswer, bound, actual), finalAnswer = open(op.finalAnswer, bound, actual))
    private def open(c: Computation, bound: String, actual: String): Computation =
      Contracts.map(c)(openNode(_, bound, actual), identity)

    private def subtype(actual: Type, expected: Type, bounds: Map[String, EffectBound], logic: Logic.Context = Logic.Context()): Boolean = (actual, expected) match {
      case (Type.LocalFunction(a, r, cs, scope, p), Type.LocalFunction(b, s, ds, target, q)) =>
        subtype(Type.Function(a, r, cs, scope, p), Type.Function(b, s, ds, target, q), bounds, logic)
      case (Type.Continuation(a, r, region, cs, p), Type.LocalFunction(b, s, ds, target, q)) =>
        subtype(Type.Function(a, r, cs, Some(region), p), Type.Function(b, s, ds, target, q), bounds, logic)
      case (Type.Function(a, r, cs, scope, parameter), Type.Function(b, s, ds, target, expectedParameter)) =>
        val id = fresh("argument")
        val v = Expr.Variable(id)
        val context = Contracts.sort(b).fold(logic)(sort => logic.bind(id, sort).assume(Contracts.member(b, v)))
        val actualResult = parameter.fold(r)(n => Contracts.substitute(r, n, v))
        val expectedResult = expectedParameter.fold(s)(n => Contracts.substitute(s, n, v))
        subtype(b, a, bounds, logic) && subtype(actualResult, expectedResult, bounds, context) && scope == target
      case (Type.CapabilityPoly(a, ops, body, cs, scope, obligations), Type.CapabilityPoly(b, expected, result, ds, target, required)) =>
        obligations.map(_.constraint.substitute(a, b)).toSet == required.map(_.constraint).toSet &&
          operationsSubtype(expected, ops.map(open(_, a, b)), bounds, logic) &&
          subtype(open(body, a, b), result, bounds, logic)
      case (Type.EffectPoly(a, body, domain), Type.EffectPoly(b, result, expectedDomain)) =>
        // The implementation must accept every effect permitted to its caller.
        val assumptions = bounds.updated(b, expectedDomain)
        accepts(Effect.Variable(b), domain, assumptions) &&
          subtype(normalize(EffectSubstitution(body, a, Effect.Variable(b)), assumptions), result, assumptions, logic)
      case (Type.TypePoly(a, body), Type.TypePoly(b, result)) =>
        subtype(Contracts.instantiate(body, a, Type.Variable(b)), result, bounds, logic)
      case (Type.PredicatePoly(a, args, body), Type.PredicatePoly(b, params, result)) if args == params =>
        val names = args.map(_ => fresh("predicateArg"))
        val renamed = Contracts.instantiatePredicate(body, a, names, Expr.Predicate(b, names.map(Expr.Variable.apply)))
        subtype(renamed, result, bounds, logic.copy(predicates = logic.predicates.updated(b, args)))
      case (a, b) if Contracts.sort(a).nonEmpty && Contracts.sort(a) == Contracts.sort(b) =>
        val name = fresh("subtype")
        val variable = Expr.Variable(name)
        proves(logic.bind(name, Contracts.sort(a).get).assume(Contracts.member(a, variable)), Contracts.member(b, variable))
      case (Type.Pair(a, b), Type.Pair(c, d)) => subtype(a, c, bounds, logic) && subtype(b, d, bounds, logic)
      // Continuation arrows are usable by A_KApp only; no S_Fun/S_Refl for them.
      case (_: Type.Continuation, _) | (_, _: Type.Continuation) => false
      case _ => TypeEquality.same(actual, expected)
    }

    /** S_Cap: operation domains are contravariant, their complete dependent
     * computation contracts covariant. Names identify the corresponding slots;
     * this does not introduce width subtyping or change handler identity.
     */
    private def operationsSubtype(actual: List[Signature], expected: List[Signature],
                                  bounds: Map[String, EffectBound], logic: Logic.Context): Boolean = {
      val left = actual.sortBy(_.name)
      val right = expected.sortBy(_.name)
      left.map(_.name) == right.map(_.name) && left.zip(right).forall { (a, b) =>
        val id = fresh("operationArgument")
        val x = Expr.Variable(id)
        val context = Contracts.sort(b.argument).fold(logic)(s =>
          logic.bind(id, s).assume(Contracts.member(b.argument, x)))
        def result(o: Signature): Computation = {
          val c = Computation(o.result, Effect.Change(o.initialAnswer, o.finalAnswer), o.returned)
          o.parameter.fold(c)(Contracts.substitute(c, _, x))
        }
        subtype(b.argument, a.argument, bounds, logic) && subtype(result(a), result(b), bounds, context)
      }
    }
    private def subtype(actual: Computation, expected: Computation, bounds: Map[String, EffectBound], logic: Logic.Context): Boolean = {
      val id = fresh("returned")
      val v = Expr.Variable(id)
      val context = Contracts.sort(actual.value).fold(logic)(sort => logic.bind(id, sort).assume(Contracts.member(actual.value, v)))
      def initial(c: Computation, i: Computation): Computation = c.returned.fold(i)(Contracts.substitute(i, _, v))
      subtype(actual.value, expected.value, bounds, logic) && ((actual.effect, expected.effect) match {
        case (Effect.Pure, Effect.Pure) => true
        case (Effect.Pure, Effect.Change(a, b)) => subtype(initial(expected, a), b, bounds, context)
        case (Effect.Change(a, b), Effect.Change(c, d)) =>
          subtype(initial(expected, c), initial(actual, a), bounds, context) && subtype(b, d, bounds, logic)
        case (a, b) => TypeEquality.same(a, b)
      })
    }

    private def answerSubtype(actual: Computation, expected: Computation, env: Environment): Boolean =
      subtype(actual, expected, env.effectBounds, env.logic)

    private def finalAnswer(tpe: Computation): Option[Computation] = tpe.effect match {
      case Effect.Pure => None
      case Effect.Variable(_) | Effect.Sequence(_, _) => fail("The paper's final-answer projection is undefined for an abstract answer effect")
      case Effect.Change(_, result) => Some(result)
    }
    private def adapt(tpe: Computation, inherited: Option[Computation], env: Environment): Computation = inherited match {
      case None =>
        require(tpe.effect == Effect.Pure, "A_App: an impure computation requires an inherited final answer")
        tpe
      case Some(answer) => tpe.effect match {
        case Effect.Pure => Computation(tpe.value, Effect.Change(answer, answer))
        case Effect.Change(initial, result) =>
          require(answerSubtype(result, answer, env),
            s"Answer mismatch: expected final ${show(answer)}, found ${show(result)}")
          tpe.copy(effect = Effect.Change(initial, answer))
        case Effect.Variable(_) | Effect.Sequence(_, _) => fail("An opaque answer effect needs instantiation before matching a final answer")
      }
    }

    private def reconstruct(from: String, to: String, env: Environment): Reconstruction =
      Evidence.reconstruct(from, to, env.delta, fresh)

    private def value(v: Value, env: Environment): CheckedValue = finishValue(inferValue(v, env))
    private def finishValue(v: CheckedValue): CheckedValue = v.copy(residuals =
      (v.residuals ++ v.children.flatMap(_.residuals) ++ v.body.toList.flatMap(_.residuals))
        .distinct.filterNot(o => v.discharged(o.name)))

    private def abstraction(source: Value, parameter: String, argument: Type, result: Option[Computation],
                            body: Term, requiredRegion: Option[String], parameterId: Option[String],
                            env: Environment): CheckedValue = {
      val alpha = fresh("bodyRegion")
      val id = parameterId.getOrElse(fresh(parameter))
      val inner = env.copy(region = alpha).bindIdentity(parameter, argument, id)
      val checked = result.fold(infer(body, inner, None))(checkTerm(body, _, inner))
      val captured = checked.residuals.filter(_.constraint.from == alpha)
      require(!checked.residuals.exists(o => o.constraint.to == alpha && o.constraint.from != alpha),
        "A_Abs: a residual mentions the body region in target position")
      val region = requiredRegion.getOrElse(Evidence.minimum(captured.map(_.constraint.to), env.delta))
      val call = Binding(fresh("callEvidence"), Constraint(alpha, region), Distance.Variable(fresh("callDistance")))
      val replacements = captured.map { obligation =>
        val path = Evidence.known(region, obligation.constraint.to, env.delta).getOrElse(
          fail(s"A_Abs: required region $region cannot discharge ${obligation.constraint}"))
        obligation.name -> Evidence.compose(Witness.Use(call), path)
      }.toMap
      val elaborated = Elaboration.substitute(checked, replacements)
      // The existing Function type does not yet retain a caller-indexed
      // declarative codomain family. Opening a fixed arrow at alpha as if it
      // meant the required region would mislabel a returned closure at calls.
      var escapesFixedRegion = false
      Contracts.map(checked.tpe)({
        case t @ Type.LocalFunction(_, _, _, Some(r), _) =>
          if r == alpha then escapesFixedRegion = true
          t
        case t @ Type.Continuation(_, _, r, _, _) =>
          if r == alpha then escapesFixedRegion = true
          t
        case t => t
      }, identity)
      require(!escapesFixedRegion,
        "A_Abs: a fixed-region result depends on the abstract caller region; supply a stable local-region result annotation", DiagnosticKind.Unsupported)
      val codomain = result.getOrElse(open(checked.tpe, alpha, region))
      CheckedValue(source, Type.Function(argument, codomain, checked.captures, Some(region), Some(id)), checked.captures,
        body = Some(elaborated), abstraction = Some(FunctionAbstraction(alpha, call)), discharged = replacements.keySet)
    }

    /** Explicit declarative T_Abs/T_App bridge, separate from A_Abs's
     * region-polymorphic arrow. No symbolic distance is assumed to be zero.
     */
    private def localAbstraction(source: Value, parameter: String, argument: Type,
        result: Option[Computation], body: Term, region: String, id: String, env: Environment): CheckedValue = {
      val inner = env.copy(region = region).bindIdentity(parameter, argument, id)
      val checked = result.fold(infer(body, inner, None))(checkTerm(body, _, inner))
      CheckedValue(source, Type.LocalFunction(argument, result.getOrElse(checked.tpe), checked.captures, Some(region), Some(id)),
        checked.captures, body = Some(checked))
    }

    private def checkValue(source: Value, expected: Type, env: Environment): CheckedValue = {
      val checked = (source, expected) match {
        case (Value.LocalLambda(parameter, argument, annotation, body, anchor), Type.LocalFunction(arg, ret, _, Some(region), binder)) =>
          require(anchor.forall(n => env.localRegion(n) == region), "Local lambda annotation names a different call region")
          val scope = env.copy(region = region)
          val domain = argument.fold(arg)(resolve(_, scope))
          val id = fresh(parameter)
          val required = binder.fold(ret)(n => Contracts.substitute(ret, n, Expr.Variable(id)))
          val result = annotation.fold(required)(resolve(_, scope.bindIdentity(parameter, domain, id)))
          finishValue(localAbstraction(source, parameter, domain, Some(result), body, region, id, env))
        case (Value.Lambda(parameter, argument, result, body), Type.Function(arg, ret, _, region, binder)) =>
          val annotationEnv = env.copy(region = region.getOrElse(env.region))
          val declaredArg = resolve(argument, annotationEnv)
          val id = fresh(parameter)
          val declaredRet = resolve(result, annotationEnv.bindIdentity(parameter, declaredArg, id))
          val required = binder.fold(ret)(n => Contracts.substitute(ret, n, Expr.Variable(id)))
          val lambda = finishValue(abstraction(source, parameter, declaredArg, Some(declaredRet), body, region, Some(id), env))
          require(subtype(declaredRet, required, env.effectBounds, env.bindIdentity(parameter, arg, id).logic),
            s"A_AbsChk: annotated result ${show(declaredRet)} does not meet ${show(required)}")
          lambda
        case (Value.InferredLambda(parameter, argument, body), Type.Function(arg, ret, _, region, binder)) =>
          require(TypeEquality.same(resolve(argument, env), arg), "A_AbsChk: lambda domain differs from the expected domain")
          val id = fresh(parameter)
          val required = binder.fold(ret)(n => Contracts.substitute(ret, n, Expr.Variable(id)))
          finishValue(abstraction(source, parameter, arg, Some(required), body, region, Some(id), env))
        case (Value.UnannotatedLambda(parameter, annotation, body), Type.Function(arg, ret, _, region, binder)) =>
          val id = fresh(parameter)
          val required = binder.fold(ret)(n => Contracts.substitute(ret, n, Expr.Variable(id)))
          val context = env.copy(region = region.getOrElse(env.region)).bindIdentity(parameter, arg, id)
          val declared = annotation.fold(required)(resolve(_, context))
          require(subtype(declared, required, env.effectBounds, context.logic),
            "A_AbsChk: lambda result annotation does not meet the expected result")
          finishValue(abstraction(source, parameter, arg, Some(declared), body, region, Some(id), env))
        case _ => value(source, env)
      }
      require(subtype(checked.tpe, expected, env.effectBounds, env.logic),
        s"Expected ${show(expected)}, found ${show(checked.tpe)}", DiagnosticKind.ContractFailure)
      checked
    }

    private def inferValue(v: Value, env: Environment): CheckedValue = v match {
      case Value.Integer(n) => CheckedValue(v, if refined then singleton(Type.Int, Expr.Integer(n)) else Type.Int, Set.empty, symbolic = Some(Expr.Integer(n)))
      case Value.Boolean(b) => CheckedValue(v, if refined then singleton(Type.Bool, Expr.Boolean(b)) else Type.Bool, Set.empty, symbolic = Some(Expr.Boolean(b)))
      case Value.Unit => CheckedValue(v, Type.Unit, Set.empty, symbolic = Some(Expr.Unit))
      case Value.Token(name) =>
        require(!env.logic.datatypes.contains(name), "A declared datatype must be constructed with its constructors")
        CheckedValue(v, Type.Token(name), Set.empty)
      case Value.Constructor(name, args) =>
        val (owner, ctor) = constructors.getOrElse(name, fail(s"Unknown constructor $name"))
        require(args.size == ctor.fields.size, s"Wrong number of arguments to constructor $name")
        var substitutions = List.empty[(String, Expr)]
        val fields = args.zip(ctor.fields).map { (arg, field) =>
          val required = substitutions.foldLeft(field._2) { case (t, (n, x)) => Contracts.substitute(t, n, x) }
          val checked = checkValue(arg, required, env)
          checked.symbolic.foreach(x => substitutions = substitutions :+ (field._1 -> x))
          checked
        }
        def project(v: CheckedValue): Expr = Contracts.base(v.tpe) match {
          case _: Type.Function | _: Type.LocalFunction | _: Type.Continuation => Expr.Unit
          case _ => symbol(v)
        }
        val expression = Option.when(refined)(Datatypes.expression(name, fields.map(project)))
        val base = Type.Data(owner)
        CheckedValue(v, if refined && expression.nonEmpty then singleton(base, expression.get) else base,
          fields.flatMap(_.captures).toSet, fields, symbolic = expression)
      case Value.Capability(name) =>
        val identity = env.capability(name)
        CheckedValue(Value.Capability(identity), Type.Capability(identity), Set(identity))
      case Value.Variable(name) =>
        val tpe = env.values.getOrElse(name, fail(s"Unknown value $name"))
        val expression = env.logicalNames.get(name).map(Expr.Variable.apply)
        CheckedValue(v, if refined && expression.nonEmpty then singleton(tpe, expression.get) else tpe, captures(tpe), symbolic = expression)
      case Value.Primitive("pair", List(a, b)) =>
        val first = value(a, env); val second = value(b, env)
        val expr = for x <- first.symbolic; y <- second.symbolic yield Expr.App("pair", List(x, y))
        val tpe = Type.Pair(first.tpe, second.tpe)
        CheckedValue(v, tpe, first.captures ++ second.captures, List(first, second), symbolic = expr)
      case Value.Primitive(name @ ("fst" | "snd"), List(argument)) =>
        val pair = value(argument, env)
        val component = Contracts.base(pair.tpe) match {
          case Type.Pair(a, b) => if name == "fst" then a else b
          case _ => fail("A projection expects a pair")
        }
        val expr = pair.symbolic.map(x => Expr.App(name, List(x)))
        CheckedValue(v, if refined && expr.nonEmpty then singleton(component, expr.get) else component,
          pair.captures, List(pair), symbolic = expr)
      case Value.Primitive(name, args) =>
        val (arguments, result, operator) = name match {
          case "zero" => (List(Type.Int), Type.Bool, "zero")
          case "not" => (List(Type.Bool), Type.Bool, "!")
          case "add" | "sub" | "mul" | "div" | "mod" =>
            (List(Type.Int, Type.Int), Type.Int, Map("add" -> "+", "sub" -> "-", "mul" -> "*", "div" -> "/", "mod" -> "%")(name))
          case "lt" | "le" | "gt" | "ge" =>
            (List(Type.Int, Type.Int), Type.Bool, Map("lt" -> "<", "le" -> "<=", "gt" -> ">", "ge" -> ">=")(name))
          case "and" | "or" => (List(Type.Bool, Type.Bool), Type.Bool, if name == "and" then "&&" else "||")
          case "eq" | "ne" =>
            val first = args.headOption.map(value(_, env)).getOrElse(fail("Equality needs two operands"))
            val base = Contracts.erase(first.tpe)
            require(Contracts.sort(base).nonEmpty && Datatypes.equalitySupported(base, datatypes), "Equality requires first-order logical operands")
            (List(base, base), Type.Bool, if name == "eq" then "==" else "!=")
          case _ => fail(s"Unknown primitive $name")
        }
        require(args.length == arguments.length, s"Wrong number of arguments to $name")
        val checked = args.zip(arguments).map { (arg, expected) =>
          val actual = value(arg, env)
          require(subtype(actual.tpe, expected, env.effectBounds, env.logic), s"$name expects ${show(expected)}, found ${show(actual.tpe)}")
          actual
        }
        val expression = if checked.forall(_.symbolic.nonEmpty) then Some(
          if operator == "zero" then Logic.eq(symbol(checked.head), Expr.Integer(0))
          else Expr.App(operator, checked.map(symbol))) else None
        if name == "div" || name == "mod" then
          require(proves(env.logic, Logic.not(Logic.eq(symbol(checked(1)), Expr.Integer(0)))), "Division requires a provably nonzero divisor")
        CheckedValue(v, if refined && expression.nonEmpty then singleton(result, expression.get) else result,
          checked.flatMap(_.captures).toSet, checked, symbolic = expression)
      case Value.LocalLambda(parameter, argument, result, body, anchor) =>
        val region = anchor.map(env.localRegion).getOrElse(env.region)
        val scope = env.copy(region = region)
        val arg = argument.map(resolve(_, scope)).getOrElse(fail("A local lambda without a domain needs an expected local arrow"))
        val id = fresh(parameter)
        val ret = result.map(resolve(_, scope.bindIdentity(parameter, arg, id)))
        localAbstraction(v, parameter, arg, ret, body, region, id, env)
      case Value.Lambda(parameter, argument, result, body) =>
        val arg = resolve(argument, env)
        val id = fresh(parameter)
        val ret = resolve(result, env.bindIdentity(parameter, arg, id))
        abstraction(v, parameter, arg, Some(ret), body, Some(env.region), Some(id), env)
      case Value.InferredLambda(parameter, argument, body) =>
        abstraction(v, parameter, resolve(argument, env), None, body, None, None, env)
      case _: Value.UnannotatedLambda =>
        fail("A lambda without a domain annotation needs an expected function type")
      case Value.CapabilityLambda(parameter, operations, body, scoped) =>
        require(!scoped, "Scoped capability tails are a prototype extension, not part of the paper algorithm", DiagnosticKind.Unsupported)
        val region = fresh(parameter)
        val ops = signatures(operations, env.withCapability(parameter, region, Nil))
        val checked = value(body, env.withCapability(parameter, region, ops))
        val (captured, _) = Evidence.generalize(region, checked.residuals)
        val cs = checked.captures - region
        CheckedValue(Value.CapabilityLambda(region, ops, body, scoped),
          Type.CapabilityPoly(region, ops, checked.tpe, cs, None, captured), cs, List(checked),
          quantified = captured.map(_.binding), discharged = captured.map(_.name).toSet)
      case Value.Instantiate(function, capability) =>
        val f = value(function, env)
        val cap = value(capability, env)
        val actual = cap.tpe match {
          case Type.Capability(name) => name
          case other => fail(s"Capability instantiation expects a capability, found ${show(other)}")
        }
        val scheme = f.tpe match {
          case p: Type.CapabilityPoly => p
          case other => fail(s"Cannot instantiate ${show(other)}")
        }
        require(operationsSubtype(env.capabilities(actual), scheme.operations.map(open(_, scheme.parameter, actual)), env.effectBounds, env.logic),
          "Capability instantiation has a different operation or answer contract")
        val requirements = Evidence.instantiate(scheme.parameter, actual, scheme.obligations, env.delta, fresh)
        CheckedValue(v, open(scheme.body, scheme.parameter, actual), f.captures ++ cap.captures, List(f, cap),
          residuals = requirements.flatMap(_.residuals), evidence = requirements.map(_.witness))
      case Value.TypeLambda(parameter, body) =>
        val id = fresh(parameter)
        val checked = value(body, env.withType(parameter, id))
        CheckedValue(Value.TypeLambda(id, body), Type.TypePoly(id, checked.tpe), checked.captures, List(checked))
      case Value.InstantiateType(function, argument) =>
        val checked = value(function, env)
        val actual = resolve(argument, env)
        // A_TApp requires a well-kinded argument in the current context, not a
        // closed type. resolve checks its scope; A_Handle still checks escape.
        val result = checked.tpe match {
          case Type.TypePoly(name, body) => Contracts.instantiate(body, name, actual)
          case other => fail(s"Cannot instantiate a type in ${show(other)}")
        }
        CheckedValue(Value.InstantiateType(function, actual), result, checked.captures, List(checked))
      case Value.PredicateLambda(parameter, args, body) =>
        val id = fresh(parameter)
        val checked = value(body, env.withPredicate(parameter, id, args))
        CheckedValue(Value.PredicateLambda(id, args, body), Type.PredicatePoly(id, args, checked.tpe), checked.captures, List(checked))
      case Value.InstantiatePredicate(function, parameters, predicate) =>
        require(parameters.map(_._1).distinct.size == parameters.size, "Duplicate predicate parameter")
        val checked = value(function, env)
        val identities = parameters.map((name, sort) => (name, fresh(name), sort))
        val inner = identities.foldLeft(env) { case (e, (name, id, sort)) =>
          e.bindIdentity(name, Contracts.typeOfSort(sort), id)
        }
        val formula = logical(predicate, inner)
        require(Logic.sort(formula, inner.logic) == Logic.Sort.Bool, "A predicate argument must return Bool")
        val result = checked.tpe match {
          case Type.PredicatePoly(name, args, body) =>
            require(args == parameters.map(_._2), "Predicate argument sorts do not match the quantified contract")
            Contracts.instantiatePredicate(body, name, identities.map(_._2), formula)
          case other => fail(s"Cannot instantiate a predicate in ${show(other)}")
        }
        CheckedValue(v, result, checked.captures, List(checked))
      case Value.EffectLambda(parameter, body, bound) =>
        val identity = fresh(parameter)
        val domain = resolve(bound, env)
        val checked = value(body, env.withEffect(parameter, identity, domain))
        CheckedValue(Value.EffectLambda(identity, body, domain), Type.EffectPoly(identity, checked.tpe, domain), checked.captures, List(checked))
      case Value.InstantiateEffect(function, effect) =>
        val checked = value(function, env)
        val actual = resolve(effect, env)
        val result = checked.tpe match {
          case Type.EffectPoly(name, body, bound) =>
            require(accepts(actual, bound, env.effectBounds), bound match {
              case EffectBound.After(prefix) => s"The answer-effect argument cannot follow ${show(prefix)}"
              case _ => "The answer-effect argument does not satisfy the repeatable bound"
            })
            normalize(EffectSubstitution(body, name, actual), env.effectBounds)
          case other => fail(s"Cannot instantiate an answer effect in ${show(other)}")
        }
        CheckedValue(v, result, checked.captures, List(checked))
    }

    private def accepts(effect: Effect, bound: EffectBound, bounds: Map[String, EffectBound]): Boolean =
      bound == EffectBound.Any

    private def compose(first: Effect, second: Effect, bounds: Map[String, EffectBound]): Effect = (first, second) match {
      case (Effect.Pure, Effect.Pure) => Effect.Pure
      case (Effect.Change(initial, finalType), Effect.Change(next, intermediate)) if TypeEquality.same(initial, intermediate) =>
        Effect.Change(next, finalType)
      case _ => fail("A_Let: answer effects do not match the inherited mode")
    }

    // Normalize after kinded answer-effect instantiation.
    private def normalize(tpe: Type, bounds: Map[String, EffectBound]): Type = tpe match {
      case Type.Function(arg, result, cs, scope, parameter) => Type.Function(normalize(arg, bounds), normalize(result, bounds), cs, scope, parameter)
      case Type.LocalFunction(arg, result, cs, scope, parameter) => Type.LocalFunction(normalize(arg, bounds), normalize(result, bounds), cs, scope, parameter)
      case Type.Pair(a, b) => Type.Pair(normalize(a, bounds), normalize(b, bounds))
      case Type.TypePoly(n, b) => Type.TypePoly(n, normalize(b, bounds))
      case Type.PredicatePoly(n, args, b) => Type.PredicatePoly(n, args, normalize(b, bounds))
      case Type.CapabilityPoly(name, ops, body, cs, scope, obligations) =>
        Type.CapabilityPoly(name, ops.map(op => op.copy(argument = normalize(op.argument, bounds), result = normalize(op.result, bounds),
          initialAnswer = normalize(op.initialAnswer, bounds), finalAnswer = normalize(op.finalAnswer, bounds))), normalize(body, bounds), cs, scope, obligations)
      case Type.EffectPoly(name, body, bound) =>
        val domain = bound match {
          case EffectBound.After(prefix) => EffectBound.After(normalize(prefix, bounds))
          case other => other
        }
        Type.EffectPoly(name, normalize(body, bounds.updated(name, domain)), domain)
      case other => other
    }
    private def normalize(tpe: Computation, bounds: Map[String, EffectBound]): Computation =
      tpe.copy(value = normalize(tpe.value, bounds), effect = normalize(tpe.effect, bounds))
    private def normalize(effect: Effect, bounds: Map[String, EffectBound]): Effect = effect match {
      case Effect.Change(a, b) => Effect.Change(normalize(a, bounds), normalize(b, bounds))
      case Effect.Sequence(a, b) => compose(normalize(a, bounds), normalize(b, bounds), bounds)
      case other => other
    }

    // Section 3.2 guarded joins/meets; contravariant positions use the dual.
    private def guarded(a: Type, b: Type, guard: Expr, join: Boolean, env: Environment): Type = {
      if TypeEquality.same(a, b) then a
      else if Contracts.sort(a).nonEmpty && Contracts.sort(a) == Contracts.sort(b) then {
        val id = fresh("guarded")
        val v = Expr.Variable(id)
        val left = Contracts.member(a, v); val right = Contracts.member(b, v)
        val predicate = if join then Expr.App("||", List(Logic.and(guard, left), Logic.and(Logic.not(guard), right)))
          else Logic.and(Expr.App("=>", List(guard, left)), Expr.App("=>", List(Logic.not(guard), right)))
        Type.Refined(id, Contracts.base(a), predicate)
      } else (a, b) match {
        case (Type.Pair(a, b), Type.Pair(c, d)) =>
          Type.Pair(guarded(a, c, guard, join, env), guarded(b, d, guard, join, env))
        case (Type.LocalFunction(a, c, cs, r, p), Type.LocalFunction(b, d, ds, s, q)) if r == s =>
          val joined = guarded(Type.Function(a, c, cs, r, p), Type.Function(b, d, ds, s, q), guard, join, env).asInstanceOf[Type.Function]
          Type.LocalFunction(joined.argument, joined.result, joined.captures, joined.region, joined.parameter)
        case (Type.Function(a, c, cs, r, p), Type.Function(b, d, ds, s, q)) if r == s =>
          val argument = guarded(a, b, guard, !join, env)
          val id = fresh("guardedArgument")
          val v = Expr.Variable(id)
          val left = p.fold(c)(Contracts.substitute(c, _, v))
          val right = q.fold(d)(Contracts.substitute(d, _, v))
          Type.Function(argument, guarded(left, right, guard, join, env.bindIdentity(id, argument, id)),
            if join then cs ++ ds else cs intersect ds, r, Some(id))
        case (Type.TypePoly(n, b), Type.TypePoly(m, c)) =>
          Type.TypePoly(n, guarded(b, Contracts.instantiate(c, m, Type.Variable(n)), guard, join, env))
        case _ => fail(s"Guarded ${if join then "join" else "meet"} is undefined for ${show(a)} and ${show(b)}")
      }
    }
    private def guarded(a: Computation, b: Computation, guard: Expr, join: Boolean, env: Environment): Computation = {
      if TypeEquality.same(a, b) then a
      else {
        val result = guarded(a.value, b.value, guard, join, env)
        val binder = if a.returned.nonEmpty || b.returned.nonEmpty then Some(fresh("guardedReturn")) else None
        val inner = binder.fold(env)(id => env.bindIdentity(id, result, id))
        def initial(c: Computation, i: Computation): Computation =
          c.returned.zip(binder).foldLeft(i) { case (i, (old, id)) => Contracts.substitute(i, old, Expr.Variable(id)) }
        val effect = (a.effect, b.effect) match {
          case (Effect.Pure, Effect.Pure) => Effect.Pure
          case (Effect.Change(ai, af), Effect.Change(bi, bf)) =>
            Effect.Change(guarded(initial(a, ai), initial(b, bi), guard, !join, inner), guarded(af, bf, guard, join, env))
          case (Effect.Pure, Effect.Change(i, finalType)) =>
            val assumption = if join then guard else Logic.not(guard)
            val scope = binder.fold(env)(id => env.bindIdentity(id, if join then a.value else result, id))
            val renamed = initial(b, i)
            require(subtype(renamed, finalType, env.effectBounds, scope.logic.assume(assumption)),
              "Mixed guarded join/meet requires pure-to-impure subtyping")
            if join then Effect.Change(renamed, finalType) else Effect.Pure
          case (Effect.Change(i, finalType), Effect.Pure) =>
            val assumption = if join then Logic.not(guard) else guard
            val scope = binder.fold(env)(id => env.bindIdentity(id, if join then b.value else result, id))
            val renamed = initial(a, i)
            require(subtype(renamed, finalType, env.effectBounds, scope.logic.assume(assumption)),
              "Mixed guarded join/meet requires pure-to-impure subtyping")
            if join then Effect.Change(renamed, finalType) else Effect.Pure
          case (a, b) if TypeEquality.same(a, b) => a
          case _ => fail("Guarded join/meet has incompatible answer effects")
        }
        Computation(result, effect, if effect == Effect.Pure then None else binder)
      }
    }

    /** The region-free surface inserts a subsumption annotation before a let
     * boundary when selfification would otherwise expose a local logical name.
     * Existential projection is covariant; its dual is used in arrow domains
     * and initial answers. Every inserted annotation is checked by S_CImpure /
     * S_Refine, rather than treated as an extra typing rule.
     */
    private def closeLet(t: CheckedTerm, outer: Environment, scope: Environment, wholeComputation: Boolean = false): CheckedTerm = {
      val locals = scope.logic.variables.keySet -- outer.logic.variables.keySet
      def escaping(c: Computation): Set[String] = if wholeComputation then Contracts.free(c) else Contracts.free(c.value) ++ (c.effect match {
        case Effect.Change(i, _) => Contracts.free(i) -- c.returned
        case _ => Set.empty[String]
      })
      if (escaping(t.tpe) intersect locals).isEmpty then t
      else {
        val domain = Logic.all(scope.logic.assumptions)
        def quantify(p: Expr, covariant: Boolean, condition: Expr): Expr = {
          val body = Logic.and(condition, if covariant then p else Logic.not(p))
          val exists = locals.toList.sorted.foldRight(body)((n, b) => Expr.Exists(n, scope.logic.variables(n), b))
          if covariant then exists else Logic.not(exists)
        }
        def ty(t: Type, covariant: Boolean, condition: Expr): Type = t match {
          case Type.Refined(n, base, predicate) =>
            Type.Refined(n, base, quantify(predicate, covariant, condition))
          case Type.Pair(a, b) => Type.Pair(ty(a, covariant, condition), ty(b, covariant, condition))
          case Type.Function(a, c, cs, r, p) =>
            Type.Function(ty(a, !covariant, condition), comp(c, covariant, condition), cs, r, p)
          case Type.LocalFunction(a, c, cs, r, p) =>
            Type.LocalFunction(ty(a, !covariant, condition), comp(c, covariant, condition), cs, r, p)
          case Type.TypePoly(n, b) => Type.TypePoly(n, ty(b, covariant, condition))
          case Type.PredicatePoly(n, args, b) => Type.PredicatePoly(n, args, ty(b, covariant, condition))
          case _ => t
        }
        def comp(c: Computation, covariant: Boolean, condition: Expr): Computation = {
          val result = ty(c.value, covariant, condition)
          c.effect match {
            case Effect.Change(i, f) =>
              val id = c.returned.getOrElse(fresh("closedReturn"))
              val antecedent = Logic.and(condition, Contracts.member(c.value, Expr.Variable(id)))
              Computation(result, Effect.Change(comp(i, !covariant, antecedent), comp(f, covariant, condition)), Some(id))
            case _ => c.copy(value = result)
          }
        }
        val projected = comp(t.tpe, true, domain)
        // A_LetF's intermediate final answer is still under the first result
        // binder. Only T2 and C2 escape; CF is replaced during composition.
        val closed = if wholeComputation then projected else (projected.effect, t.tpe.effect) match {
          case (Effect.Change(i, _), Effect.Change(_, f)) => projected.copy(effect = Effect.Change(i, f))
          case _ => projected
        }
        require((escaping(closed) intersect locals).isEmpty, "A_Let: a local logical variable escapes its scope")
        require(subtype(t.tpe, closed, scope.effectBounds, t.logic), "A_Let: inferred closing annotation failed subsumption")
        CheckedTerm(Term.Ascribe(t.source, closed), closed, t.captures, children = List(t),
          logic = t.logic, symbolic = t.symbolic, residuals = t.residuals,
          coercedFrom = Option.unless(TypeEquality.same(t.tpe.value, closed.value))(t.tpe.value))
      }
    }

    private def checkTerm(term: Term, expected: Computation, env: Environment): CheckedTerm =
      checkTermInMode(term, expected, env, finalAnswer(expected))

    private def inferMatch(source: Term, scrutinee: Value, cases: List[MatchCase], env: Environment,
        inherited: Option[Computation], expected: Option[Computation]): CheckedTerm = {
      val v = value(scrutinee, env)
      val owner = Contracts.base(v.tpe) match {
        case Type.Data(n) => n
        case _ => fail("Pattern matching requires an algebraic datatype")
      }
      val schema = datatypes.find(_.name == owner).get.constructors
      require(cases.map(_.constructor).distinct.size == cases.size, "Duplicate match constructor")
      require(cases.map(_.constructor).toSet == schema.map(_.name).toSet, "Match must cover every constructor exactly once")
      val branches = cases.map { c =>
        val fields = schema.find(_.name == c.constructor).get.fields
        require(c.parameters.size == fields.size, "Wrong number of pattern fields")
        require(c.parameters.distinct.size == c.parameters.size, "Duplicate pattern binder")
        var bound = env
        var substitutions = List.empty[(String, Expr)]
        val bindings = c.parameters.zip(fields).map { case (n, (field, tpe)) =>
          val t = substitutions.foldLeft(tpe) { case (t, (n, x)) => Contracts.substitute(t, n, x) }
          bound = bound.bind(n, t)
          bound.logicalNames.get(n).foreach(id => substitutions = substitutions :+ (field -> Expr.Variable(id)))
          n -> t
        }
        val equality = if refined then Logic.eq(symbol(v), Datatypes.expression(c.constructor,
          c.parameters.map(n => bound.logicalNames.get(n).fold[Expr](Expr.Unit)(Expr.Variable.apply)))) else Logic.True
        val scope = bound.copy(logic = bound.logic.assume(equality))
        val guard = c.parameters.foldRight(equality) { (n, p) =>
          bound.logicalNames.get(n).fold(p)(id => Expr.Exists(id, bound.logic.variables(id), p))
        }
        val body = expected.fold(closeLet(infer(c.body, scope, inherited), env, scope, wholeComputation = true))(
          checkTermInMode(c.body, _, scope, inherited))
        (bindings, guard, body)
      }
      val result = expected.getOrElse(branches.init.foldRight(branches.last._3.tpe) {
        case ((_, guard, branch), rest) => guarded(branch.tpe, rest, guard, true, env)
      })
      symbolicTerm(CheckedTerm(source, result, v.captures ++ branches.flatMap(_._3.captures),
        List(v), branches.map(b => ascribe(b._3, result)), logic = env.logic,
        patternBindings = branches.map(_._1)), env)
    }

    // The explicit mode is needed by the return-clause bridge: synthesize in
    // empty mode, then apply declarative subsumption to the required answer.
    // This is not the printed A_SubC mode conclusion when expected is impure.
    private def checkTermInMode(term: Term, expected: Computation, env: Environment,
                                inherited: Option[Computation]): CheckedTerm = {
      val checked = term match {
        case Term.Match(scrutinee, cases) => finishTerm(inferMatch(term, scrutinee, cases, env, inherited, Some(expected)))
        case Term.InferredHandle(cap, operations, body, returned, onReturn) =>
          finishTerm(inferHandler(cap, expected, operations, body, returned, onReturn, env, inherited))
        case Term.Return(v) =>
          val checked = checkValue(v, expected.value, env)
          CheckedTerm(term, adapt(Computation(checked.tpe), inherited, env), checked.captures,
            List(checked), logic = env.logic, symbolic = checked.symbolic, residuals = checked.residuals)
        case _ => infer(term, env, inherited)
      }
      require(subtype(checked.tpe, expected, env.effectBounds, checked.logic), s"Expected ${show(expected)}, found ${show(checked.tpe)}", DiagnosticKind.ContractFailure)
      ascribe(checked, expected)
    }

    private def ascribe(checked: CheckedTerm, expected: Computation): CheckedTerm = {
      // Lowering must use the checked contract after subsumption rather than
      // the narrower inferred head type. Captures are only emitter metadata.
      checked.copy(tpe = expected, coercedFrom =
        if TypeEquality.same(checked.tpe.value, expected.value) then None else Some(checked.tpe.value))
    }

    private def infer(term: Term, env: Environment, inherited: Option[Computation]): CheckedTerm =
      finishTerm(inferTerm(term, env, inherited))

    private def finishTerm(checked: CheckedTerm): CheckedTerm = {
      checked.copy(residuals = (checked.residuals ++ checked.values.flatMap(_.residuals) ++
        checked.children.flatMap(_.residuals) ++ checked.operations.flatMap(_.body.residuals)).distinct)
    }
    private def inferTerm(term: Term, env: Environment, inherited: Option[Computation]): CheckedTerm = term match {
      case Term.Match(scrutinee, cases) => inferMatch(term, scrutinee, cases, env, inherited, None)
      case Term.TypeAlias(name, definition, body) =>
        val alias = resolve(definition, env)
        val checked = infer(body, env.copy(aliases = env.aliases.updated(name, alias), types = env.types - name), inherited)
        CheckedTerm(term, checked.tpe, checked.captures, children = List(checked), logic = checked.logic, symbolic = checked.symbolic)
      case Term.Ascribe(body, expected) =>
        val checked = checkTerm(body, resolve(expected, env), env)
        CheckedTerm(term, adapt(checked.tpe, inherited, env), checked.captures, children = List(checked), logic = checked.logic, symbolic = checked.symbolic)
      case Term.Assert(condition) =>
        val c = value(condition, env)
        require(Contracts.base(c.tpe) == Type.Bool, "An assertion must be Bool")
        require(proves(env.logic, symbol(c)), s"Refinement assertion is not valid: ${Logic.show(symbol(c))}")
        CheckedTerm(term, adapt(Computation(Type.Unit), inherited, env), c.captures, List(c), logic = env.logic)
      case Term.ExplicitReturn(_) => fail("return effect is only permitted as a handler return clause")
      case Term.LetRec(name, parameter, argument, result, definition, body, local) =>
        val arg = resolve(argument, env)
        val id = fresh(parameter)
        val ret = resolve(result, env.bindIdentity(parameter, arg, id))
        val recursive = if local then Type.LocalFunction(arg, ret, env.capabilities.keySet, Some(env.region), Some(id))
          else Type.Function(arg, ret, env.capabilities.keySet, Some(env.region), Some(id))
        val scope = env.bind(name, recursive)
        val lambda = if local then finishValue(localAbstraction(Value.LocalLambda(parameter, Some(argument), Some(result), definition),
          parameter, arg, Some(ret), definition, env.region, id, scope))
          else finishValue(abstraction(Value.Lambda(parameter, argument, result, definition), parameter,
            arg, Some(ret), definition, Some(env.region), Some(id), scope))
        val rest = infer(body, scope, inherited)
        CheckedTerm(term, rest.tpe, lambda.captures ++ rest.captures,
          values = List(lambda), children = List(lambda.body.get, rest), logic = rest.logic, symbolic = rest.symbolic)
      case Term.Return(v) =>
        val checked = value(v, env)
        CheckedTerm(term, adapt(Computation(checked.tpe), inherited, env), checked.captures, List(checked), logic = env.logic, symbolic = checked.symbolic)
      case Term.Let(name, binding, body) =>
        val first = infer(binding, env, inherited)
        val nextAnswer = first.tpe.effect match {
          case Effect.Pure => inherited
          case Effect.Variable(_) | Effect.Sequence(_, _) => None
          case Effect.Change(initial, _) => Some(initial)
        }
        val scope = env.copy(logic = first.logic).bind(name, first.tpe.value, first.symbolic)
        val second = closeLet(infer(body, scope, nextAnswer), env, scope)
        val effect = (first.tpe.effect, second.tpe.effect) match {
          case (Effect.Change(middle, finalAnswer), Effect.Change(initial, actual)) if refined =>
            require(answerSubtype(actual, middle, env.copy(logic = second.logic)), "Sequential refined answer types do not compose")
            Effect.Change(initial, finalAnswer)
          case _ => compose(first.tpe.effect, second.tpe.effect, env.effectBounds)
        }
        symbolicTerm(CheckedTerm(term, Computation(second.tpe.value, effect, second.tpe.returned), first.captures ++ second.captures,
          children = List(first, second)), env)
      case Term.Apply(function, argument) =>
        val f = value(function, env)
        val (param, declaredResult, parameter, reconstruction) = f.tpe match {
          case Type.Function(param, result, _, region, parameter) =>
            (param, result, parameter, Some(reconstruct(env.region, region.getOrElse(fail("Unresolved function region")), env)))
          case Type.LocalFunction(param, result, _, Some(region), parameter) =>
            require(env.region == region, "A local function must be called at exactly its declared region")
            (param, result, parameter, None)
          case Type.Continuation(param, result, region, _, parameter) =>
            require(env.region == region, "A_KApp: continuation application requires exactly its ambient region")
            (param, result, parameter, None)
          case other => fail(s"Cannot call ${show(other)}")
        }
        val arg = checkValue(argument, param, env)
        val result = parameter.filter(n => Contracts.free(declaredResult).contains(n))
          .fold(declaredResult)(n => Contracts.substitute(declaredResult, n, symbol(arg)))
        symbolicTerm(CheckedTerm(term, adapt(result, inherited, env), f.captures ++ arg.captures, List(f, arg),
          residuals = reconstruction.toList.flatMap(_.residuals), evidence = reconstruction.toList.map(_.witness)), env)
      case Term.If(condition, whenTrue, whenFalse) =>
        val c = value(condition, env)
        require(Contracts.base(c.tpe) == Type.Bool, "An if condition must be Bool")
        val conditionExpr = c.symbolic.getOrElse(Logic.True)
        val leftEnv = if refined then env.copy(logic = env.logic.assume(conditionExpr)) else env
        val rightEnv = if refined then env.copy(logic = env.logic.assume(Logic.not(conditionExpr))) else env
        val left = infer(whenTrue, leftEnv, inherited)
        val right = infer(whenFalse, rightEnv, inherited)
        val context = if !refined then env.logic else Logic.Context(
          left.logic.variables ++ right.logic.variables, left.logic.predicates ++ right.logic.predicates,
          env.logic.assumptions ++ List(Expr.App("=>", List(conditionExpr, Logic.all(left.logic.assumptions))),
            Expr.App("=>", List(Logic.not(conditionExpr), Logic.all(right.logic.assumptions)))) , env.logic.datatypes, env.logic.measures)
        val joined = guarded(left.tpe, right.tpe, conditionExpr, true, env)
        CheckedTerm(term, joined, c.captures ++ left.captures ++ right.captures, List(c),
          List(ascribe(left, joined), ascribe(right, joined)), logic = context,
          symbolic = for a <- left.symbolic; b <- right.symbolic yield Expr.App("ite", List(conditionExpr, a, b)))
      case Term.Perform(receiver, operation, argument) =>
        // A value binder shadows a handler name in receiver position. Capability
        // aliases retain the original identity, including its answer contract.
        val cap = env.values.get(receiver) match {
          case Some(Type.Capability(name)) => name
          case Some(other) => fail(s"Operation receiver $receiver has non-capability type ${show(other)}")
          case None => env.capability(receiver)
        }
        val ops = env.capabilities.getOrElse(cap, fail(s"Unknown or out-of-scope capability $cap"))
        val declared = ops.find(_.name == operation).getOrElse(fail(s"Unknown operation $cap.$operation"))
        val arg = checkValue(argument, declared.argument, env)
        val supplied = declared.parameter.fold(declared)(_ => operationArgument(declared, symbol(arg)))
        val resultIdentity = if supplied.returned.nonEmpty then Some(fresh("operationResult")) else None
        val op = supplied.returned.zip(resultIdentity).foldLeft(supplied) { case (o, (parameter, actual)) =>
          o.copy(initialAnswer = Contracts.substitute(o.initialAnswer, parameter, Expr.Variable(actual)), returned = None)
        }
        val answer = inherited.getOrElse(fail(s"$cap.$operation needs an inherited final answer type"))
        val reconstruction = reconstruct(env.region, cap, env)
        val context = try {
          Evidence.atc(answer, op.finalAnswer, reconstruction.witness.distance, answerSubtype(_, _, env))
        } catch {
          case error: Diagnostic =>
            observe.foreach(_(OperationInference(cap, operation, reconstruction, Left(error))))
            throw error
        }
        observe.foreach(_(OperationInference(cap, operation, reconstruction, Right(context))))
        val initial = context.plug(op.initialAnswer)
        val target = if env.values.contains(receiver) then receiver else cap
        val checked = CheckedTerm(Term.Perform(target, operation, argument), Computation(op.result, Effect.Change(initial, answer), resultIdentity),
          arg.captures + cap, List(arg), operationArgument = Some(op.argument),
          residuals = reconstruction.residuals, evidence = List(reconstruction.witness),
          operationTyping = Some(OperationTyping(op, context, Computation(op.result, Effect.Change(initial, answer), resultIdentity))))
        resultIdentity match {
          case None => symbolicTerm(checked, env)
          case Some(id) =>
            val sort = Contracts.sort(op.result).getOrElse(fail("Dependent operation results require a logical base type"))
            checked.copy(logic = env.logic.bind(id, sort).assume(Contracts.member(op.result, Expr.Variable(id))), symbolic = Some(Expr.Variable(id)))
        }
      case Term.Handle(cap, result, operations, body, returned, onReturn) =>
        inferHandler(cap, resolve(result, env), operations, body, returned, onReturn, env, inherited)
      case _: Term.InferredHandle =>
        fail("A handler without a result annotation needs an expected computation type")
    }

    private def inferHandler(cap: String, finalType: Computation, operations: List[Operation], body: Term,
                             returned: String, onReturn: Term, env: Environment,
                             inherited: Option[Computation]): CheckedTerm = {
      val identity = fresh(cap)
      require(operations.nonEmpty, "A handler must declare at least one operation")
      require(operations.map(_.name).distinct.size == operations.size, "Duplicate operation in handler")
      val ops = operations.map { op =>
        val signature = resolveSignature(op.signature.copy(parameter = if refined && Contracts.sort(resolve(op.argument, env)).nonEmpty then Some(op.parameter) else None), env)
        op.copy(argument = signature.argument, result = signature.result,
          initialAnswer = signature.initialAnswer, finalAnswer = signature.finalAnswer,
          argumentIdentity = signature.parameter, returned = signature.returned)
      }
      val edge = Binding(fresh("handlerEvidence"), Constraint(identity, env.region), Distance.Numeral(1))
      val inner = env.enterHandler(cap, identity, ops.map(_.signature), edge)
      val checkedBody = infer(body, inner, Some(finalType))
      require(!checkedBody.residuals.exists(_.constraint.free(identity)), "A_Handle: a residual obligation mentions the local handler region")
      require(!free(checkedBody.tpe.value).contains(identity),
        "A_Handle: the return-clause parameter type mentions the local handler region")
      val initial = checkedBody.tpe.effect match {
        case Effect.Change(initial, result) if TypeEquality.same(result, finalType) => initial
        case _ => fail("Handler body did not produce the required answer transition")
      }
      require(!free(initial).contains(identity), s"Capability $cap escapes through the handler's return contract")
      val returnEnv = env.bind(returned, checkedBody.tpe.value)
      // Preserve A_HRet's empty inherited mode. For impure expected answers,
      // use an explicit synthesis/subsumption bridge (see FIDELITY.md).
      val returnAnswer = checkedBody.tpe.returned.fold(initial)(id =>
        Contracts.substitute(initial, id, Expr.Variable(returnEnv.logicalNames.getOrElse(returned,
          fail("Dependent handler answer requires a logical return value")))))
      val checkedReturn = onReturn match {
        // Explicit declarative T_HRet checking: the clause runs outside the
        // handled region but may itself have the annotated answer effect.
        case Term.ExplicitReturn(body) => checkTerm(body, returnAnswer, returnEnv)
        case _ => checkTermInMode(onReturn, returnAnswer, returnEnv, None)
      }
      val checkedOps = ops.map { op =>
        require(op.parameter != op.continuation, "An operation argument and resumption need distinct names")
        val k = Type.Continuation(op.result, op.initialAnswer, env.region, env.capabilities.keySet, op.returned)
        val scope = op.argumentIdentity.fold(env.bind(op.parameter, op.argument))(id => env.bindIdentity(op.parameter, op.argument, id))
        val clause = checkTerm(op.body, op.finalAnswer, scope.bind(op.continuation, k))
        CheckedOperation(op, clause)
      }
      val cs = (checkedBody.captures - identity) ++ checkedReturn.captures ++ checkedOps.flatMap(_.body.captures)
      symbolicTerm(CheckedTerm(Term.Handle(identity, finalType, operations, body, returned, onReturn), adapt(finalType, inherited, env), cs,
        children = List(checkedBody, checkedReturn), operations = checkedOps, handlerEvidence = Some(edge)), env)
    }

  }
}

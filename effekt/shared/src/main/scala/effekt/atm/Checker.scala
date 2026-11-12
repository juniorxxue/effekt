package effekt.atm

import Syntax.*
import Evidence.*
import Annotations.{Kind, Argument, Proof}

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
                         patternBindings: List[List[(String, Type)]] = Nil,
                         callContexts: List[AnswerContext] = Nil)
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
                                   // Lexical default for unqualified arrows inside an annotation.
                                   // Result annotations use the fresh caller; domains use the outer region.
                                   annotationDefaultRegion: Option[String] = None,
                                   delta: List[Binding] = Nil,
                                   types: Map[String, String] = Map.empty,
                                   aliases: Map[String, Type] = Map.empty,
                                   annotations: Map[String, Argument] = Map.empty,
                                   annotationResiduals: Map[String, List[Obligation]] = Map.empty,
                                   kinds: Map[String, Kind] = Map.empty,
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
      def withAnnotation(name: String, actual: Argument, residuals: List[Obligation] = Nil): Environment =
        copy(annotations = annotations.updated(name, actual), annotationResiduals = annotationResiduals.updated(name, residuals),
          types = types - name, aliases = aliases - name, effects = effects - name, predicateNames = predicateNames - name)
      def withKind(name: String, identity: String, kind: Kind): Environment = {
        val next = withAnnotation(name, Annotations.variable(identity, kind)).copy(kinds = kinds.updated(identity, kind))
        kind match {
          // A declared evidence parameter is an assumption, not an inferred
          // numeric distance. Keep reconstruction's functional graph invariant.
          case Kind.Evidence(a, d, b) if a != b && !delta.exists(_.constraint.from == a) =>
            next.copy(delta = Binding(identity, Constraint(a, b), d) :: delta)
          case _ => next
        }
      }
      def withCaller(name: Option[String], caller: String): Environment =
        name.fold(this)(withKind(_, caller, Kind.Region))
      def withType(name: String, identity: String): Environment = copy(types = types.updated(name, identity), aliases = aliases - name, annotations = annotations - name)
      def withPredicate(name: String, identity: String, args: List[Logic.Sort]): Environment =
        copy(predicateNames = predicateNames.updated(name, identity), logic = logic.copy(predicates = logic.predicates.updated(identity, args)))
      def withEffect(name: String, identity: String, bound: EffectBound): Environment =
        copy(effects = effects.updated(name, identity), effectBounds = effectBounds.updated(identity, bound), annotations = annotations - name)
      def capability(name: String): String = names.getOrElse(name, fail(s"Unknown or out-of-scope capability $name"))
      def localRegion(name: String): String = name match {
        case "top" | Evidence.Top => Evidence.Top
        case _ => annotations.get(name) match {
          case Some(Argument.Region(r)) => r
          case Some(_) => fail(s"$name is not a region")
          case None => values.get(name) match {
            case Some(Type.Capability(r)) => r
            case _ => names.getOrElse(name, aliases.get(name) match {
              case Some(Type.FixedFunction(_, _, _, Some(r), _)) => r
              case _ => capability(name)
            })
          }
        }
      }
      // A new capability binder shadows an older value with the same name.
      // A later value binder is selected by inferValue before this capability.
      def withCapability(name: String, identity: String, ops: List[Signature]): Environment =
        copy(capabilities = capabilities.updated(identity, ops), names = names.updated(name, identity),
          values = values - name, logicalNames = logicalNames - name, annotations = annotations - name)
      def enterHandler(name: String, identity: String, ops: List[Signature], edge: Binding): Environment =
        withCapability(name, identity, ops).copy(region = identity, delta = edge :: delta)
    }

    private case class InterfaceSchema(parameters: List[String], operations: Map[String, Signature])
    private var interfaces = Map.empty[String, InterfaceSchema]

    def check(program: Program): Either[Diagnostic, CheckedProgram] = try {
      if !program.inferResult && program.result.effect != Effect.Pure then fail("A closed program must have a pure final computation type")
      refined = Contracts.usesRefinements(program)
      datatypes = Datatypes.validate(program.datatypes)
      val logicalData = Datatypes.logical(datatypes)
      val measures = Measures.validate(program.measures, logicalData)
      var env = Environment(logic = Logic.Context(datatypes = logicalData, measures = measures))
      require(program.typeAliases.map(_._1).distinct.size == program.typeAliases.size, "Duplicate type alias")
      program.typeAliases.foreach { (name, tpe) =>
        env = env.copy(aliases = env.aliases.updated(name, resolve(tpe, env)))
      }
      datatypes = datatypes.map(d => d.copy(constructors = d.constructors.map { c =>
        var scope = env
        c.copy(fields = c.fields.map { (name, tpe) =>
          val t = resolve(tpe, scope)
          scope = scope.bindIdentity(name, t, name)
          (name, t)
        })
      }))
      constructors = Datatypes.constructors(datatypes)
      require(program.interfaces.map(_.name).distinct.size == program.interfaces.size, "Duplicate interface")
      interfaces = program.interfaces.map { i =>
        require(i.operations.map(_.name).distinct.size == i.operations.size, s"Duplicate operation in interface ${i.name}")
        require(i.typeParameters.distinct.size == i.typeParameters.size, "Duplicate interface type parameter")
        val parameters = i.typeParameters.map(fresh)
        val scope = i.typeParameters.zip(parameters).foldLeft(env) { case (e, (n, id)) => e.withType(n, id) }
        i.name -> InterfaceSchema(parameters, i.operations.map { op =>
          val unit = Computation(Type.Unit)
          op.name -> resolveSignature(Signature(op.name, op.argument, op.result, unit, unit,
            op.parameter, op.returned), scope)
        }.toMap)
      }.toMap
      val annotation = Option.unless(program.inferResult)(resolve(program.result, env))
      val checked = annotation.fold(infer(program.body, env, None))(checkTerm(program.body, _, env))
      val expected = annotation.getOrElse(checked.tpe)
      require(expected.effect == Effect.Pure, "A closed program must have a pure final computation type")
      if free(expected).nonEmpty then fail("The result type contains an escaping capability")
      require(checked.residuals.isEmpty, s"Unsolved region obligations at program boundary: ${checked.residuals}")
      Elaboration.validate(checked)
      Right(new CheckedProgram(if program.inferResult then program.copy(result = expected, inferResult = false) else program, checked, datatypes))
    } catch { case d: Diagnostic => Left(d) }

    private def fail(message: String): Nothing = throw Diagnostic(message)
    private def require(condition: Boolean, message: => String, kind: DiagnosticKind = DiagnosticKind.TypeError): Unit =
      if !condition then throw Diagnostic(message, kind)

    private def free(tpe: Type): Set[String] = tpe match {
      case Type.Capability(name) => Set(name)
      case Type.Continuation(arg, result, region, _, _) => free(arg) ++ free(result) + region
      case f: Type.Function => Annotations.regions(Computation(f))
      case Type.FixedFunction(arg, result, _, context, _) => free(arg) ++ free(result) ++ context.toSet.filter(_ != Evidence.Top)
      case Type.CapabilityPoly(name, ops, body, cs, scope, obligations) =>
        (free(body) ++ obligations.flatMap(_.constraint.free) ++ ops.flatMap(free) - name) ++ scope.toSet.filter(_ != Evidence.Top)
      case Type.EffectPoly(_, body, bound) => free(body) ++ free(bound)
      case Type.Refined(_, base, _) => free(base)
      case Type.Pair(a, b) => free(a) ++ free(b)
      case Type.TypePoly(_, body) => free(body)
      case Type.PredicatePoly(_, _, body) => free(body)
      case _: Type.KindPoly | _: Type.AbstractResult => Annotations.regions(Computation(tpe))
      case _ => Set.empty
    }
    private def free(op: Signature): Set[String] =
      free(op.argument) ++ free(op.result) ++ free(op.initialAnswer) ++ free(op.finalAnswer)
    private def free(tpe: Computation): Set[String] =
      if tpe.form.nonEmpty then Annotations.regions(tpe) else free(tpe.value) ++ free(tpe.effect)
    private def free(effect: Effect): Set[String] = effect match {
      case Effect.Pure | Effect.Variable(_) | Effect.Abstract => Set.empty
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
      case Type.Function(_, _, cs, _, _, _)  => cs
      case Type.FixedFunction(_, _, cs, _, _) => cs
      case Type.Continuation(_, _, _, cs, _) => cs
      case Type.CapabilityPoly(_, _, _, cs, _, obligations) => cs
      case Type.EffectPoly(_, body, _) => captures(body)
      case Type.TypePoly(_, body) => captures(body)
      case Type.PredicatePoly(_, _, body) => captures(body)
      case Type.KindPoly(_, _, body) => captures(body)
      case _ => Set.empty
    }
    private def resolve(tpe: Type, env: Environment): Type = tpe match {
      case Type.Pair(a, b) => Type.Pair(resolve(a, env), resolve(b, env))
      case Type.Token(name) if env.annotations.contains(name) => fail(s"$name is not a value type")
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
      case Type.KindPoly(parameter, k, body) =>
        val kind = resolveKind(k, env)
        val id = fresh(parameter)
        Type.KindPoly(id, kind, resolve(body, env.withKind(parameter, id, kind)))
      case _: Type.AbstractResult => fail("An abstract computation is not a source value type")
      case Type.TypePoly(parameter, body) =>
        val id = fresh(parameter)
        Type.TypePoly(id, resolve(body, env.withType(parameter, id)))
      case Type.PredicatePoly(parameter, args, body) =>
        val id = fresh(parameter)
        Type.PredicatePoly(id, args, resolve(body, env.withPredicate(parameter, id, args)))
      case Type.Capability(name) =>
        Type.Capability(env.names.getOrElse(name, fail(s"Unknown capability type $name")))
      case Type.FixedFunction(arg, result, cs, region, parameter) =>
        val target = region.map(r => if r == Evidence.Top then r else env.localRegion(r)).getOrElse(env.region)
        val scope = env.copy(region = target)
        val argument = resolve(arg, scope)
        val id = parameter.map(fresh)
        val inner = parameter.zip(id).foldLeft(scope) { case (e, (n, i)) => e.bindIdentity(n, argument, i) }
        // Legacy emitter metadata: a local callback may capture any enclosing
        // capability reachable from its exact call region. This is not an
        // additional source subtyping or region condition.
        val reachable = env.capabilities.keySet.filter(c => Evidence.known(target, c, env.delta).nonEmpty)
        Type.FixedFunction(argument, resolve(result, inner), cs.map(env.capability) ++ reachable, Some(target), id)
      case Type.Function(arg, result, cs, _, parameter, template) =>
        val caller = Caller(fresh("typeCaller"), fresh("typeDistance"))
        val target = if cs.isEmpty then env.annotationDefaultRegion.getOrElse(env.region) else Evidence.minimum(cs.toList.map(env.capability), env.delta)
        val call = Binding(fresh("typeCallEvidence"), Constraint(caller.region, target), Distance.Variable(caller.distance))
        val identity = parameter.map(fresh)
        val argument = resolve(arg, env)
        var inner = env.copy(region = caller.region,
          annotationDefaultRegion = Some(env.annotationDefaultRegion.getOrElse(env.region)),
          delta = call :: env.delta, kinds = env.kinds.updated(caller.distance, Kind.Distance))
          .withCaller(template.map(_.region).filter(_.nonEmpty), caller.region)
        val contexts = template.toList.flatMap(_.contexts).map { (n, k) =>
          val kind = resolveKind(k, inner); val id = fresh(n)
          inner = inner.withKind(n, id, kind); (id, kind)
        }
        inner = parameter.zip(identity).foldLeft(inner) { case (e, (name, id)) => e.bindIdentity(name, argument, id) }
        Type.Function(argument, resolve(result, inner), cs.map(env.capability), Some(target), identity,
          Some(caller.copy(contexts = contexts)))
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
      tpe match {
        case Computation(Type.Token(n), Effect.Pure, None, None) => env.annotations.get(n) match {
          case Some(Argument.Computation(c)) => return c
          case _ => ()
        }
        case _ => ()
      }
      resolveComputation(tpe, env)
    }
    private def resolveComputation(tpe: Computation, env: Environment): Computation = tpe.form match {
      case Some(ComputationForm.Variable(name)) => env.annotations.get(name) match {
        case Some(Argument.Computation(c)) => c
        case _ => fail(s"Unknown or non-computation variable $name")
      }
      case Some(ComputationForm.Context(k, a)) => resolveContext(k, env).plug(resolve(a, env))
      case None =>
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
      case Effect.Abstract => fail("Unresolved abstract computation effect")
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
    private def declaredSignature(op: Operation, env: Environment): Option[Signature] = op.interfaceName.map { name =>
      val schema = interfaces.getOrElse(name, fail(s"Unknown interface $name"))
      require(schema.parameters.size == op.interfaceArguments.size, s"Wrong number of type arguments for interface $name")
      val signature = schema.operations.getOrElse(op.name, fail(s"Operation ${op.name} is not declared in interface $name"))
      val actuals = schema.parameters.zip(op.interfaceArguments.map(resolve(_, env))).toMap
      val mapper = new Annotations.Mapper {
        override def after(t: Type): Type = t match {
          case Type.Variable(n) if actuals.contains(n) => actuals(n)
          case _ => t
        }
      }
      mapper.signature(signature)
    }
    private def resolveSignature(op: Signature, env: Environment, inferredAnswer: Option[Computation] = None,
        declared: Option[Signature] = None, argumentAnnotated: Boolean = true,
        resultAnnotated: Boolean = true, argumentAnnotations: List[Option[Type]] = Nil): Signature = {
      val arg = if argumentAnnotated then resolve(op.argument, env) else declared.get.argument
      declared.foreach(d => require(TypeEquality.same(arg, d.argument), s"Argument type of ${op.name} does not match its interface"))
      if argumentAnnotations.nonEmpty then {
        val components = Surface.components(arg, argumentAnnotations.size)
        argumentAnnotations.zip(components).foreach { (annotation, expected) =>
          annotation.foreach(t => require(TypeEquality.same(resolve(t, env), expected),
            s"Argument type of ${op.name} does not match its interface"))
        }
      }
      val parameter = if refined && Contracts.sort(arg).nonEmpty then op.parameter.map(fresh) else None
      val scope = op.parameter.zip(parameter).foldLeft(env) { case (e, (name, id)) => e.bindIdentity(name, arg, id) }
      val declaredResult = declared.map { d =>
        d.parameter.zip(parameter).foldLeft(d.result) { case (t, (old, id)) =>
          Contracts.substitute(t, old, Expr.Variable(id))
        }
      }
      val result = if resultAnnotated then resolve(op.result, scope) else declaredResult.get
      declaredResult.foreach(t => require(TypeEquality.same(result, t), s"Result type of ${op.name} does not match its interface"))
      require(op.returned.isEmpty || Contracts.sort(result).nonEmpty,
        "Dependent operation results require an Int, Bool, Unit or logical pair type")
      val returned = op.returned.map(fresh)
      val inner = op.returned.zip(returned).foldLeft(scope) { case (e, (name, id)) => e.bindIdentity(name, result, id) }
      // A contextual answer is already resolved in the enclosing scope. Do not
      // resolve its generative regions or refinement identities a second time.
      op.copy(argument = arg, result = result, initialAnswer = inferredAnswer.getOrElse(resolve(op.initialAnswer, inner)),
        finalAnswer = inferredAnswer.getOrElse(resolve(op.finalAnswer, scope)), parameter = parameter, returned = returned)
    }
    private def operationArgument(op: Signature, argument: Expr): Signature = op.parameter.fold(op) { parameter =>
      op.copy(result = Contracts.substitute(op.result, parameter, argument),
        initialAnswer = Contracts.substitute(op.initialAnswer, parameter, argument),
        finalAnswer = Contracts.substitute(op.finalAnswer, parameter, argument), parameter = None)
    }

    private def open(tpe: Type, bound: String, actual: String): Type =
      Annotations.substitution(bound, Argument.Region(actual)).typ(tpe)
    private def open(op: Signature, bound: String, actual: String): Signature =
      Annotations.substitution(bound, Argument.Region(actual)).signature(op)
    private def open(c: Computation, bound: String, actual: String): Computation =
      Annotations.substitution(bound, Argument.Region(actual)).computation(c)

    private def resolveDistance(d: Distance, env: Environment): Distance = d match {
      case Distance.Numeral(n) => require(n >= 0, "A distance must be nonnegative"); d
      case Distance.Variable(n) => env.annotations.get(n) match {
        case Some(Argument.Distance(d)) => d
        case _ => fail(s"Unknown or non-distance variable $n")
      }
      case Distance.Plus(a, b) => Distance.Plus(resolveDistance(a, env), resolveDistance(b, env))
      case Distance.Projected(p) =>
        val proof = resolveProof(p, env)
        require(proof.residuals.isEmpty, "A distance projection needs an available evidence path")
        proof.witness.distance
    }
    private def resolveKind(k: Kind, env: Environment): Kind = k match {
      case Kind.Evidence(a, d, b) => Kind.Evidence(env.localRegion(a), resolveDistance(d, env), env.localRegion(b))
      case Kind.Context(d) => Kind.Context(resolveDistance(d, env))
      case _ => k
    }
    private def resolveContext(k: AnswerContext, env: Environment): AnswerContext = k match {
      case AnswerContext.Hole => k
      case AnswerContext.Variable(n) => env.annotations.get(n) match {
        case Some(Argument.Context(c)) => c
        case _ => fail(s"Unknown or non-ATC variable $n")
      }
      case AnswerContext.Compose(a, b) => Annotations.contextCompose(resolveContext(a, env), resolveContext(b, env))
      case AnswerContext.Layer(t, i, rest, binder) =>
        val v = resolve(t, env)
        val id = binder.map(fresh)
        val inner = binder.zip(id).foldLeft(env) { case (e, (n, b)) => e.bindIdentity(n, v, b) }
        // The layer's returned-value binder scopes only over its initial answer.
        AnswerContext.Layer(v, resolve(i, inner), resolveContext(rest, env), id)
    }
    private def resolveProof(p: Proof, env: Environment): Reconstruction = p match {
      case Proof.Current => Reconstruction(Witness.Refl(env.region), Nil)
      case Proof.Refl(r) => Reconstruction(Witness.Refl(env.localRegion(r)), Nil)
      case Proof.Step(h) =>
        val r = env.localRegion(h)
        val edge = env.delta.find(b => b.constraint.from == r && b.distance == Distance.Numeral(1))
          .getOrElse(fail(s"No in-scope handler edge for $h"))
        Reconstruction(Witness.Use(edge), Nil)
      case Proof.Variable(n) => env.annotations.get(n) match {
        case Some(Argument.Evidence(Proof.Checked(w))) => Reconstruction(w, env.annotationResiduals.getOrElse(n, Nil))
        case _ => fail(s"Unknown or non-evidence variable $n")
      }
      case Proof.Compose(a, b) =>
        val x = resolveProof(a, env); val y = resolveProof(b, env)
        Reconstruction(Evidence.compose(x.witness, y.witness), (x.residuals ++ y.residuals).distinct)
      case Proof.Path(r) => reconstruct(env.region, env.localRegion(r), env)
      case _: Proof.Checked => fail("Internal evidence cannot be supplied as a source annotation")
    }
    private def resolveArgument(a: Argument, env: Environment): (Argument, List[Obligation]) = a match {
      case Argument.Region(r) => (Argument.Region(env.localRegion(r)), Nil)
      case Argument.Distance(d) => (Argument.Distance(resolveDistance(d, env)), Nil)
      case Argument.Context(k) => (Argument.Context(resolveContext(k, env)), Nil)
      case Argument.Computation(c) => (Argument.Computation(resolve(c, env)), Nil)
      case Argument.Evidence(p) =>
        val result = resolveProof(p, env)
        (Argument.Evidence(Proof.Checked(result.witness)), result.residuals)
    }

    private def subtype(actual: Type, expected: Type, bounds: Map[String, EffectBound], logic: Logic.Context = Logic.Context()): Boolean = (actual, expected) match {
      case (Type.FixedFunction(a, r, cs, scope, p), Type.FixedFunction(b, s, ds, target, q)) =>
        subtype(Type.Function(a, r, cs, scope, p), Type.Function(b, s, ds, target, q), bounds, logic)
      case (Type.Continuation(a, r, region, cs, p), Type.FixedFunction(b, s, ds, target, q)) =>
        subtype(Type.Function(a, r, cs, Some(region), p), Type.Function(b, s, ds, target, q), bounds, logic)
      case (af @ Type.Function(a, r, cs, scope, parameter, ac), bf @ Type.Function(b, s, ds, target, expectedParameter, bc)) =>
        val id = fresh("argument")
        val v = Expr.Variable(id)
        val region = fresh("subtypeCaller"); val distance = Distance.Variable(fresh("subtypeDistance"))
        val aa = af.argument; val ba = bf.argument
        val context = Contracts.sort(ba).fold(logic)(sort => logic.bind(id, sort).assume(Contracts.member(ba, v)))
        val ar = FunctionTypes.result(af, region, distance)
        val br = FunctionTypes.result(bf, region, distance)
        val actualParameters = FunctionTypes.contexts(af, region, distance)
        val expectedParameters = FunctionTypes.contexts(bf, region, distance)
        var alignedResult = ar
        val compatible = actualParameters.size == expectedParameters.size && actualParameters.zip(expectedParameters).forall {
          case ((n, k), (m, l)) =>
            val ok = Annotations.sameKind(k, l)
            val subst = Annotations.substitution(n, Annotations.variable(m, l))
            alignedResult = subst.computation(alignedResult)
            ok
        }
        val actualResult = parameter.fold(alignedResult)(n => Contracts.substitute(alignedResult, n, v))
        val expectedResult = expectedParameter.fold(br)(n => Contracts.substitute(br, n, v))
        compatible && subtype(ba, aa, bounds, logic) && subtype(actualResult, expectedResult, bounds, context) && scope == target
      case (Type.CapabilityPoly(a, ops, body, cs, scope, obligations), Type.CapabilityPoly(b, expected, result, ds, target, required)) =>
        obligations.map(_.constraint.substitute(a, b)).toSet == required.map(_.constraint).toSet &&
          operationsSubtype(expected, ops.map(open(_, a, b)), bounds, logic) &&
          subtype(open(body, a, b), result, bounds, logic)
      case (Type.EffectPoly(a, body, domain), Type.EffectPoly(b, result, expectedDomain)) =>
        // The implementation must accept every effect permitted to its caller.
        val assumptions = bounds.updated(b, expectedDomain)
        accepts(Effect.Variable(b), domain, assumptions) &&
          subtype(normalize(EffectSubstitution(body, a, Effect.Variable(b)), assumptions), result, assumptions, logic)
      case (Type.KindPoly(a, k, body), Type.KindPoly(b, l, result)) =>
        Annotations.sameKind(k, l) && subtype(Annotations.substitution(a, Annotations.variable(b, l)).typ(body), result, bounds, logic)
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
      if actual.form.nonEmpty || expected.form.nonEmpty then return (actual.form, expected.form) match {
        case (Some(ComputationForm.Context(a, c)), Some(ComputationForm.Context(b, d))) =>
          TypeEquality.sameContext(a, b) && subtype(c, d, bounds, logic)
        case _ => TypeEquality.same(actual, expected)
      }
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

    private def finalAnswer(tpe: Computation): Option[Computation] = if tpe.form.nonEmpty then None else tpe.effect match {
      case Effect.Pure => None
      case Effect.Variable(_) | Effect.Sequence(_, _) | Effect.Abstract => fail("The paper's final-answer projection is undefined for an abstract answer effect")
      case Effect.Change(_, result) => Some(result)
    }
    private def adapt(tpe: Computation, inherited: Option[Computation], env: Environment): Computation = inherited match {
      case None =>
        require(tpe.form.nonEmpty || tpe.effect == Effect.Pure, "A_App: an impure computation requires an inherited final answer")
        tpe
      case Some(answer) => tpe.effect match {
        case Effect.Pure => Computation(tpe.value, Effect.Change(answer, answer))
        case Effect.Change(initial, result) =>
          require(answerSubtype(result, answer, env),
            s"Answer mismatch: expected final ${show(answer)}, found ${show(result)}")
          tpe.copy(effect = Effect.Change(initial, answer))
        case Effect.Variable(_) | Effect.Sequence(_, _) | Effect.Abstract => fail("An opaque answer effect needs instantiation before matching a final answer")
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
                            env: Environment, resultCaller: Option[Caller] = None, callerName: Option[String] = None): CheckedValue = {
      val alpha = fresh("bodyRegion")
      val id = parameterId.getOrElse(fresh(parameter))
      val distanceName = fresh("callDistance")
      val opening = FunctionTypes.opening(resultCaller, alpha, Distance.Variable(distanceName))
      // A_Abs introduces its fresh caller after the argument type has been checked.
      val domain = argument
      val inner = env.copy(region = alpha, annotationDefaultRegion = None,
        kinds = env.kinds.updated(distanceName, Kind.Distance)).withCaller(callerName, alpha).bindIdentity(parameter, domain, id)
      val annotation = result.map(opening.computation)
      val checked = annotation.fold(infer(body, inner, None))(checkTerm(body, _, inner))
      val captured = checked.residuals.filter(_.constraint.from == alpha)
      require(!checked.residuals.exists(o => o.constraint.to == alpha && o.constraint.from != alpha),
        "A_Abs: a residual mentions the body region in target position")
      val region = requiredRegion.getOrElse(Evidence.minimum(captured.map(_.constraint.to), env.delta))
      val call = Binding(fresh("callEvidence"), Constraint(alpha, region), Distance.Variable(distanceName))
      val replacements = captured.map { obligation =>
        val path = Evidence.known(region, obligation.constraint.to, env.delta).getOrElse(
          fail(s"A_Abs: required region $region cannot discharge ${obligation.constraint}"))
        obligation.name -> Evidence.compose(Witness.Use(call), path)
      }.toMap
      val elaborated = Elaboration.substitute(checked, replacements)
      val codomain = annotation.getOrElse(elaborated.tpe)
      CheckedValue(source, Type.Function(domain, codomain, checked.captures, Some(region), Some(id),
        Some(Caller(alpha, distanceName))), checked.captures,
        body = Some(elaborated), abstraction = Some(FunctionAbstraction(alpha, call)), discharged = replacements.keySet)
    }

    /** Explicit declarative T_Abs/T_App bridge, separate from A_Abs's
     * region-polymorphic arrow. No symbolic distance is assumed to be zero.
     */
    private def fixedAbstraction(source: Value, parameter: String, argument: Type,
        result: Option[Computation], body: Term, region: String, id: String, env: Environment): CheckedValue = {
      val inner = env.copy(region = region).bindIdentity(parameter, argument, id)
      val checked = result.fold(infer(body, inner, None))(checkTerm(body, _, inner))
      CheckedValue(source, Type.FixedFunction(argument, result.getOrElse(checked.tpe), checked.captures, Some(region), Some(id)),
        checked.captures, body = Some(checked))
    }

    private def contextAbstraction(source: Value, parameter: String, argument: Type, result: Computation,
        parameters: List[(String, Kind)], body: Term, env: Environment, callerName: Option[String]): CheckedValue = {
      def targets(d: Distance): List[String] = d match {
        case Distance.Projected(Proof.Path(r)) if !callerName.contains(r) => List(env.localRegion(r))
        case Distance.Plus(a, b) => targets(a) ++ targets(b)
        case _ => Nil
      }
      val required = parameters.flatMap {
        case (_, Kind.Context(d)) => targets(d)
        case _ => fail("Call-context parameters must have ATC kind")
      }
      val target = if required.isEmpty then env.region else Evidence.minimum(required, env.delta)
      val caller = Caller(fresh("bodyRegion"), fresh("callDistance"))
      val call = Binding(fresh("callEvidence"), Constraint(caller.region, target), Distance.Variable(caller.distance))
      val id = fresh(parameter)
      val arg = resolve(argument, env)
      var inner = env.copy(region = caller.region, delta = call :: env.delta,
        kinds = env.kinds.updated(caller.distance, Kind.Distance)).withCaller(callerName, caller.region)
      val contexts = parameters.map { (n, k) =>
        val kind = resolveKind(k, inner); val name = fresh(n)
        inner = inner.withKind(n, name, kind); (name, kind)
      }
      inner = inner.bindIdentity(parameter, arg, id)
      val annotation = resolve(result, inner.copy(annotationDefaultRegion = Some(env.region)))
      val checked = checkTerm(body, annotation, inner)
      require(!checked.residuals.exists(o => o.constraint.from == caller.region),
        "A_Abs: declared call-context requirements do not discharge the body's region obligations")
      require(!checked.residuals.exists(o => o.constraint.to == caller.region),
        "A_Abs: a residual mentions the body region in target position")
      CheckedValue(source, Type.Function(arg, annotation, checked.captures, Some(target), Some(id),
        Some(caller.copy(contexts = contexts))), checked.captures, body = Some(checked),
        abstraction = Some(FunctionAbstraction(caller.region, call)))
    }

    private def checkValue(source: Value, expected: Type, env: Environment): CheckedValue = {
      val checked = (source, expected) match {
        case (lambda @ (Value.FixedLambda(_, _, _, _, _) | Value.Lambda(_, _, _, _, None) |
            Value.InferredLambda(_, _, _, None) | Value.UnannotatedLambda(_, _, _, None)),
            Type.FixedFunction(arg, ret, _, Some(region), binder)) =>
          val (parameter, argument, annotation, body, anchor) = lambda match {
            case Value.FixedLambda(p, a, r, b, at) => (p, a, r, b, at)
            case Value.Lambda(p, a, r, b, None) => (p, Some(a), Some(r), b, None)
            case Value.InferredLambda(p, a, b, None) => (p, Some(a), None, b, None)
            case Value.UnannotatedLambda(p, r, b, None) => (p, None, r, b, None)
            case _ => fail("Caller-region binders belong to ordinary functions")
          }
          require(anchor.forall(n => env.localRegion(n) == region), "Fixed-region lambda annotation names a different call region")
          val scope = env.copy(region = region)
          val domain = argument.fold(arg)(resolve(_, scope))
          val id = fresh(parameter)
          val required = binder.fold(ret)(n => Contracts.substitute(ret, n, Expr.Variable(id)))
          val result = annotation.fold(required)(resolve(_, scope.bindIdentity(parameter, domain, id)))
          val source = Value.FixedLambda(parameter, argument, annotation, body, anchor)
          finishValue(fixedAbstraction(source, parameter, domain, Some(result), body, region, id, env))
        case (lambda @ (Value.Lambda(_, _, _, _, _) | Value.InferredLambda(_, _, _, _) | Value.UnannotatedLambda(_, _, _, _)), f: Type.Function) =>
          val (parameter, argument, annotation, body, callerName) = lambda match {
            case Value.Lambda(p, a, r, b, name) => (p, Some(a), Some(r), b, name)
            case Value.InferredLambda(p, a, b, name) => (p, Some(a), None, b, name)
            case Value.UnannotatedLambda(p, r, b, name) => (p, None, r, b, name)
          }
          val caller = Caller(fresh("annotationCaller"), fresh("annotationDistance"))
          val arg = argument.fold(f.argument)(resolve(_, env.copy(annotationDefaultRegion = f.region)))
          val id = fresh(parameter)
          val opened = FunctionTypes.result(f, caller.region, Distance.Variable(caller.distance))
          val required = f.parameter.fold(opened)(n => Contracts.substitute(opened, n, Expr.Variable(id)))
          val context = env.copy(region = caller.region, annotationDefaultRegion = f.region).withCaller(callerName, caller.region).bindIdentity(parameter, arg, id)
          val declared = annotation.fold(required)(resolve(_, context))
          require(subtype(declared, required, env.effectBounds, context.logic),
            s"A_AbsChk: Expected ${show(required)}, found result annotation ${show(declared)}", DiagnosticKind.ContractFailure)
          finishValue(abstraction(source, parameter, arg, Some(declared), body, f.region, Some(id), env, Some(caller), callerName))
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
          case _: Type.Function | _: Type.FixedFunction | _: Type.Continuation => Expr.Unit
          case _ => symbol(v)
        }
        val expression = Option.when(refined)(Datatypes.expression(name, fields.map(project)))
        val base = Type.Data(owner)
        CheckedValue(v, if refined && expression.nonEmpty then singleton(base, expression.get) else base,
          fields.flatMap(_.captures).toSet, fields, symbolic = expression)
      case Value.Capability(name) =>
        val identity = env.capability(name)
        CheckedValue(Value.Capability(identity), Type.Capability(identity), Set(identity))
      case Value.Variable(name) if !env.values.contains(name) && env.names.contains(name) =>
        inferValue(Value.Capability(name), env)
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
      case Value.FixedLambda(parameter, argument, result, body, anchor) =>
        val region = anchor.map(env.localRegion).getOrElse(env.region)
        val scope = env.copy(region = region)
        val arg = argument.map(resolve(_, scope)).getOrElse(fail("A fixed-region lambda without a domain needs an expected fixed-region arrow"))
        val id = fresh(parameter)
        val ret = result.map(resolve(_, scope.bindIdentity(parameter, arg, id)))
        fixedAbstraction(v, parameter, arg, ret, body, region, id, env)
      case Value.ContextLambda(p, a, r, ks, b, name) => contextAbstraction(v, p, a, r, ks, b, env, name)
      case Value.Lambda(parameter, argument, result, body, callerName) =>
        val caller = Caller(fresh("annotationCaller"), fresh("annotationDistance"))
        val arg = resolve(argument, env)
        val id = fresh(parameter)
        val ret = resolve(result, env.copy(region = caller.region, annotationDefaultRegion = Some(env.region)).withCaller(callerName, caller.region).bindIdentity(parameter, arg, id))
        abstraction(v, parameter, arg, Some(ret), body, Some(env.region), Some(id), env, Some(caller), callerName)
      case Value.InferredLambda(parameter, argument, body, callerName) =>
        val caller = Caller(fresh("annotationCaller"), fresh("annotationDistance"))
        abstraction(v, parameter, resolve(argument, env),
          None, body, None, None, env, Some(caller), callerName)
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
      case Value.KindLambda(parameter, k, body) =>
        val kind = resolveKind(k, env)
        val id = fresh(parameter)
        val checked = value(body, env.withKind(parameter, id, kind))
        val quantified = kind match {
          case Kind.Evidence(a, d, b) => List(Binding(id, Constraint(a, b), d))
          case _ => Nil
        }
        CheckedValue(Value.KindLambda(id, kind, body), Type.KindPoly(id, kind, checked.tpe), checked.captures,
          List(checked), quantified = quantified)
      case Value.InstantiatePolymorphic(function, alternatives) =>
        instantiateBare(value(function, env), alternatives, env)
      case Value.InstantiateKind(function, argument) =>
        instantiate(v, value(function, env), env)
      case Value.TypeLambda(parameter, body) =>
        val id = fresh(parameter)
        val checked = value(body, env.withType(parameter, id))
        CheckedValue(Value.TypeLambda(id, body), Type.TypePoly(id, checked.tpe), checked.captures, List(checked))
      case Value.InstantiateType(function, argument) =>
        instantiate(v, value(function, env), env)
      case Value.PredicateLambda(parameter, args, body) =>
        val id = fresh(parameter)
        val checked = value(body, env.withPredicate(parameter, id, args))
        CheckedValue(Value.PredicateLambda(id, args, body), Type.PredicatePoly(id, args, checked.tpe), checked.captures, List(checked))
      case Value.InstantiatePredicate(function, parameters, predicate) =>
        instantiate(v, value(function, env), env)
      case Value.EffectLambda(parameter, body, bound) =>
        val identity = fresh(parameter)
        val domain = resolve(bound, env)
        val checked = value(body, env.withEffect(parameter, identity, domain))
        CheckedValue(Value.EffectLambda(identity, body, domain), Type.EffectPoly(identity, checked.tpe, domain), checked.captures, List(checked))
      case Value.InstantiateEffect(function, effect) =>
        instantiate(v, value(function, env), env)

    }

    private def instantiateBare(checked: CheckedValue, alternatives: List[PolymorphicArgument], env: Environment): CheckedValue = {
        def selected(p: PartialFunction[PolymorphicArgument, Value]): Value =
          alternatives.collectFirst(p).getOrElse(fail(s"Polymorphic argument has no syntax matching ${show(checked.tpe)}"))
        val application = checked.tpe match {
          case Type.KindPoly(_, kind, _) => selected {
            case PolymorphicArgument.Kind(a) if ((kind, a) match {
              case (Kind.Region, _: Argument.Region) | (Kind.Distance, _: Argument.Distance) |
                   (Kind.Computation, _: Argument.Computation) | (_: Kind.Context, _: Argument.Context) |
                   (_: Kind.Evidence, _: Argument.Evidence) => true
              case _ => false
            }) => Value.InstantiateKind(checked.source, a)
          }
          case _: Type.TypePoly => selected { case PolymorphicArgument.ValueType(t) => Value.InstantiateType(checked.source, t) }
          case _: Type.EffectPoly => selected { case PolymorphicArgument.AnswerEffect(e) => Value.InstantiateEffect(checked.source, e) }
          case _: Type.PredicatePoly => selected { case PolymorphicArgument.Predicate(ps, b) => Value.InstantiatePredicate(checked.source, ps, b) }
          case other => fail(s"Cannot instantiate a polymorphic argument in ${show(other)}")
        }
        instantiate(application, checked, env)
    }

    private def callValue(v: Value, env: Environment): (CheckedValue, List[AnswerContext]) = v match {
      case Value.InstantiatePolymorphic(f, alternatives) =>
        val (checked, contexts) = callValue(f, env)
        checked.tpe match {
          case fn: Type.Function if fn.caller.exists(_.contexts.nonEmpty) =>
            val k = alternatives.collectFirst { case PolymorphicArgument.Kind(Argument.Context(k)) => k }
              .getOrElse(fail("A call-context argument must be an ATC"))
            (checked, contexts :+ resolveContext(k, env))
          case _ =>
            require(contexts.isEmpty, "Unexpected call-context arguments")
            (instantiateBare(checked, alternatives, env), Nil)
        }
      case Value.InstantiateKind(f, Argument.Context(k)) =>
        val (checked, contexts) = callValue(f, env)
        checked.tpe match {
          case fn: Type.Function if fn.caller.exists(_.contexts.nonEmpty) => (checked, contexts :+ resolveContext(k, env))
          case _ => (instantiate(v, checked, env), contexts)
        }
      case _ => (value(v, env), Nil)
    }

    // Both tagged and bare applications use these same instantiation rules.
    // The function is checked once, before selecting a bare argument's kind.
    private def instantiate(v: Value, checked: CheckedValue, env: Environment): CheckedValue = v match {
      case Value.InstantiateKind(function, argument) =>
        val (actual, residuals) = resolveArgument(argument, env)
        val result = checked.tpe match {
          case Type.KindPoly(name, kind, body) =>
            val actualKind = Annotations.kindOf(actual, env.kinds)
            require(Annotations.sameKind(actualKind, kind), s"Annotation kind mismatch: expected $kind, found $actualKind")
            Annotations.substitution(name, actual).typ(body)
          case other => fail(s"Cannot instantiate an annotation in ${show(other)}")
        }
        val evs = actual match {
          case Argument.Evidence(Proof.Checked(w)) => List(w)
          case _ => Nil
        }
        CheckedValue(Value.InstantiateKind(function, actual), result, checked.captures, List(checked),
          residuals = residuals, evidence = evs)
      case Value.InstantiateType(function, argument) =>
        val actual = resolve(argument, env)
        // A_TApp requires a well-kinded argument in the current context, not a
        // closed type. resolve checks its scope; A_Handle still checks escape.
        val result = checked.tpe match {
          case Type.TypePoly(name, body) => Contracts.instantiate(body, name, actual)
          case other => fail(s"Cannot instantiate a type in ${show(other)}")
        }
        CheckedValue(Value.InstantiateType(function, actual), result, checked.captures, List(checked))
      case Value.InstantiatePredicate(function, parameters, predicate) =>
        require(parameters.map(_._1).distinct.size == parameters.size, "Duplicate predicate parameter")
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
      case Value.InstantiateEffect(function, effect) =>
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
      case _ => fail("Expected a resolved polymorphic application")
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
      case Type.Function(arg, result, cs, scope, parameter, caller) => Type.Function(normalize(arg, bounds), normalize(result, bounds), cs, scope, parameter, caller)
      case Type.FixedFunction(arg, result, cs, scope, parameter) => Type.FixedFunction(normalize(arg, bounds), normalize(result, bounds), cs, scope, parameter)
      case Type.Pair(a, b) => Type.Pair(normalize(a, bounds), normalize(b, bounds))
      case Type.TypePoly(n, b) => Type.TypePoly(n, normalize(b, bounds))
      case Type.KindPoly(n, k, b) => Type.KindPoly(n, k, normalize(b, bounds))
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
    private def normalize(tpe: Computation, bounds: Map[String, EffectBound]): Computation = {
      val m = new Annotations.Mapper {
        override def effect(e: Effect): Effect = e match {
          case Effect.Sequence(a, b) => compose(effect(a), effect(b), bounds)
          case _ => super.effect(e)
        }
      }
      m.computation(tpe)
    }
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
        case (Type.FixedFunction(a, c, cs, r, p), Type.FixedFunction(b, d, ds, s, q)) if r == s =>
          val joined = guarded(Type.Function(a, c, cs, r, p), Type.Function(b, d, ds, s, q), guard, join, env).asInstanceOf[Type.Function]
          Type.FixedFunction(joined.argument, joined.result, joined.captures, joined.region, joined.parameter)
        case (af @ Type.Function(a, c, cs, r, p, ac), bf @ Type.Function(b, d, ds, s, q, bc)) if r == s =>
          val caller = Caller(fresh("guardedCaller"), fresh("guardedDistance"))
          val distance = Distance.Variable(caller.distance)
          val leftParameters = FunctionTypes.contexts(af, caller.region, distance)
          val rightParameters = FunctionTypes.contexts(bf, caller.region, distance)
          require(leftParameters.size == rightParameters.size && leftParameters.zip(rightParameters).forall {
            case ((_, k), (_, l)) => Annotations.sameKind(k, l)
          }, "Guarded function types require compatible call-context telescopes")
          def align(c: Computation): Computation = rightParameters.zip(leftParameters).foldLeft(c) {
            case (c, ((n, _), (m, k))) => Annotations.substitution(n, Annotations.variable(m, k)).computation(c)
          }
          val argument = guarded(af.argument, bf.argument, guard, !join, env)
          val id = fresh("guardedArgument")
          val v = Expr.Variable(id)
          val ar = FunctionTypes.result(af, caller.region, distance)
          val br = align(FunctionTypes.result(bf, caller.region, distance))
          val left = p.fold(ar)(Contracts.substitute(ar, _, v))
          val right = q.fold(br)(Contracts.substitute(br, _, v))
          Type.Function(argument, guarded(left, right, guard, join, env.bindIdentity(id, argument, id)),
            if join then cs ++ ds else cs intersect ds, r, Some(id), Some(caller.copy(contexts = leftParameters)))
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
          case Type.Function(a, c, cs, r, p, caller) =>
            Type.Function(ty(a, !covariant, condition), comp(c, covariant, condition), cs, r, p, caller)
          case Type.FixedFunction(a, c, cs, r, p) =>
            Type.FixedFunction(ty(a, !covariant, condition), comp(c, covariant, condition), cs, r, p)
          case Type.TypePoly(n, b) => Type.TypePoly(n, ty(b, covariant, condition))
          case Type.KindPoly(n, k, b) => Type.KindPoly(n, k, ty(b, covariant, condition))
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
      case Term.AnnotationAlias(name, definition, body) =>
        val (alias, residuals) = resolveArgument(definition, env)
        val checked = infer(body, env.withAnnotation(name, alias, residuals), inherited)
        CheckedTerm(term, checked.tpe, checked.captures, children = List(checked), logic = checked.logic, symbolic = checked.symbolic)
      case Term.TypeAlias(name, definition, body) =>
        val alias = resolve(definition, env)
        val checked = infer(body, env.copy(aliases = env.aliases.updated(name, alias), types = env.types - name, annotations = env.annotations - name), inherited)
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
      case Term.LetRec(name, parameter, argument, result, definition, body, anchor, callerName) =>
        val local = anchor.nonEmpty
        require(anchor.forall(r => env.localRegion(r) == env.region), "A recursive fixed-region definition must use its enclosing lexical region")
        require(!local || callerName.isEmpty, "Caller-region binders belong to ordinary functions")
        val caller = Option.unless(local)(Caller(fresh("recursiveCaller"), fresh("recursiveDistance")))
        val arg = resolve(argument, env)
        val id = fresh(parameter)
        val ret = resolve(result, env.copy(region = caller.fold(env.region)(_.region), annotationDefaultRegion = Some(env.region)).withCaller(callerName, caller.fold(env.region)(_.region)).bindIdentity(parameter, arg, id))
        val recursive = if local then Type.FixedFunction(arg, ret, env.capabilities.keySet, Some(env.region), Some(id))
          else Type.Function(arg, ret, env.capabilities.keySet, Some(env.region), Some(id), caller)
        val scope = env.bind(name, recursive)
        val lambda = if local then finishValue(fixedAbstraction(Value.FixedLambda(parameter, Some(argument), Some(result), definition),
          parameter, arg, Some(ret), definition, env.region, id, scope))
          else finishValue(abstraction(Value.Lambda(parameter, argument, result, definition, callerName), parameter,
            arg, Some(ret), definition, Some(env.region), Some(id), scope, caller, callerName))
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
          case Effect.Variable(_) | Effect.Sequence(_, _) | Effect.Abstract => None
          case Effect.Change(initial, _) => Some(initial)
        }
        val scope = env.copy(logic = first.logic).bind(name, first.tpe.value, first.symbolic)
        val second = closeLet(infer(body, scope, nextAnswer), env, scope)
        if first.tpe.effect == Effect.Pure && second.tpe.form.nonEmpty then
          return symbolicTerm(CheckedTerm(term, second.tpe, first.captures ++ second.captures,
            children = List(first, second)), env)
        val effect = (first.tpe.effect, second.tpe.effect) match {
          case (Effect.Change(middle, finalAnswer), Effect.Change(initial, actual)) if refined =>
            require(answerSubtype(actual, middle, env.copy(logic = second.logic)), "Sequential refined answer types do not compose")
            Effect.Change(initial, finalAnswer)
          case _ => compose(first.tpe.effect, second.tpe.effect, env.effectBounds)
        }
        symbolicTerm(CheckedTerm(term, Computation(second.tpe.value, effect, second.tpe.returned), first.captures ++ second.captures,
          children = List(first, second)), env)
      case Term.Apply(function, argument) =>
        val (f, contexts) = callValue(function, env)
        val (param, declaredResult, parameter, reconstruction) = f.tpe match {
          case fn: Type.Function =>
            val ev = reconstruct(env.region, fn.region.getOrElse(fail("Unresolved function region")), env)
            val parameters = FunctionTypes.contexts(fn, env.region, ev.witness.distance)
            require(parameters.size == contexts.size, s"Function expects ${parameters.size} explicit ATC arguments, found ${contexts.size}")
            var codomain = FunctionTypes.result(fn, env.region, ev.witness.distance)
            parameters.zip(contexts).foreach { case ((name, kind), context) =>
              require(Annotations.sameKind(kind, Kind.Context(Annotations.depth(context, env.kinds))), "Call-context ATC depth differs from its declared distance")
              val subst = Annotations.substitution(name, Argument.Context(context))
              codomain = subst.computation(codomain)
            }
            (fn.argument, codomain, fn.parameter, Some(ev))
          case Type.FixedFunction(param, result, _, Some(region), parameter) =>
            require(env.region == region, "A fixed-region function must be called at exactly its declared region")
            (param, result, parameter, None)
          case Type.Continuation(param, result, region, _, parameter) =>
            require(env.region == region, "A_KApp: continuation application requires exactly its ambient region")
            (param, result, parameter, None)
          case other => fail(s"Cannot call ${show(other)}")
        }
        require(f.tpe.isInstanceOf[Type.Function] || contexts.isEmpty, "Only an ordinary function accepts call-context arguments")
        val arg = checkValue(argument, param, env)
        val result = parameter.filter(n => Contracts.free(declaredResult).contains(n))
          .fold(declaredResult)(n => Contracts.substitute(declaredResult, n, symbol(arg)))
        symbolicTerm(CheckedTerm(term, adapt(result, inherited, env), f.captures ++ arg.captures, List(f, arg),
          residuals = reconstruction.toList.flatMap(_.residuals), evidence = reconstruction.toList.map(_.witness), callContexts = contexts), env)
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
        inferOperation(receiver, operation, argument, None, None, env, inherited)
      case Term.AnnotatedPerform(receiver, operation, argument, evidence, context) =>
        inferOperation(receiver, operation, argument, evidence, context, env, inherited)
      case Term.SingleAnnotatedPerform(receiver, operation, argument, name) =>
        val actual = env.annotations.getOrElse(name, fail(s"Unknown operation annotation $name"))
        Annotations.kindOf(actual, env.kinds) match {
          case _: Kind.Evidence =>
            inferOperation(receiver, operation, argument, Some(Proof.Variable(name)), None, env, inherited)
          case _: Kind.Context =>
            inferOperation(receiver, operation, argument, None, Some(AnswerContext.Variable(name)), env, inherited)
          case _ => fail(s"Operation annotation $name must have evidence or ATC kind")
        }
      case Term.Handle(cap, result, operations, body, returned, onReturn) =>
        inferHandler(cap, resolve(result, env), operations, body, returned, onReturn, env, inherited)
      case Term.InferredHandle(cap, operations, body, returned, onReturn) =>
        val result = handlerResultValue(cap, operations, body, returned, onReturn, env, Map.empty)
        val candidate = inherited.fold(Computation(result))(answer =>
          Computation(result, Effect.Change(answer, answer)))
        // A_Handle is checking-only. Generate an annotation, then check it in
        // exactly pi(candidate) mode; do not treat this projection as a typing
        // derivation or equate an identity answer transition with purity.
        inferHandler(cap, candidate, operations, body, returned, onReturn, env, inherited)
    }

    /** Partial surface annotation inference, additional to the printed algorithm.
     * Project the value returned by a handler without using its answer effects.
     * The complete handler is subsequently checked by inferHandler, including
     * all arguments, clauses, evidence, residuals, and scope-exit conditions.
     * Refinements are forgotten in the proposed value type, never assumed.
     */
    private type OperationResults = Map[String, Map[String, Type]]
    private def handlerResultValue(cap: String, operations: List[Operation], body: Term,
        returned: String, onReturn: Term, env: Environment, headers: OperationResults): Type = {
      val results = operations.map { op =>
        val declared = declaredSignature(op, env)
        val argument = if op.argumentAnnotated then resolve(op.argument, env) else declared.get.argument
        val result = if op.resultAnnotated then resolve(op.result, env.bind(op.parameter, argument)) else declared.get.result
        op.name -> Contracts.erase(result)
      }.toMap
      // A header records only result types. It must not impersonate a checked
      // capability, or allow the new handler's name to escape through a value.
      val inner = env.copy(values = env.values - cap, names = env.names - cap)
      val bodyValue = projectedValue(body, inner, headers.updated(cap, results))
      val result = projectedValue(onReturn, env.bind(returned, bodyValue), headers - returned)
      require(!result.isInstanceOf[Type.AbstractResult],
        "Handler result inference needs a known value type; add a computation annotation", DiagnosticKind.Unsupported)
      result
    }

    private def projectedValue(term: Term, env: Environment, headers: OperationResults): Type = {
      def unsupported(): Nothing = throw Diagnostic(
        "Cannot infer the handler's returned value type here; add a computation annotation", DiagnosticKind.Unsupported)
      def result(v: Value, scope: Environment = env): Type = {
        // Do not synthesize bodies of source abstractions during this pass:
        // their region/evidence checks belong to the subsequent full check.
        def atomic(v: Value): Boolean = v match {
          case _: Value.Integer | _: Value.Boolean | Value.Unit | _: Value.Token | _: Value.Variable | _: Value.Capability => true
          case Value.Primitive(_, args) => args.forall(atomic)
          case Value.Constructor(_, args) => args.forall(atomic)
          case Value.Instantiate(f, a) => atomic(f) && atomic(a)
          case Value.InstantiateType(f, _) => atomic(f)
          case Value.InstantiateEffect(f, _) => atomic(f)
          case Value.InstantiatePredicate(f, _, _) => atomic(f)
          case Value.InstantiateKind(f, _) => atomic(f)
          case Value.InstantiatePolymorphic(f, _) => atomic(f)
          case _ => false
        }
        v match {
          case Value.Lambda(p, a, r, _, name) =>
            val argument = resolve(a, scope)
            val caller = Caller(fresh("projectedCaller"), fresh("projectedDistance"))
            val inner = scope.copy(region = caller.region, annotationDefaultRegion = Some(scope.region))
              .withCaller(name, caller.region).bind(p, argument)
            Contracts.erase(Type.Function(argument, resolve(r, inner), region = Some(scope.region), caller = Some(caller)))
          case _ if atomic(v) => Contracts.erase(value(v, scope).tpe)
          case _ => unsupported()
        }
      }
      def operation(receiver: String, name: String): Type = {
        val header = if env.values.contains(receiver) then None else headers.get(receiver)
        header match {
          case Some(ops) => ops.getOrElse(name, fail(s"Unknown operation $receiver.$name"))
          case None =>
            val identity = env.values.get(receiver) match {
              case Some(Type.Capability(r)) => r
              case Some(other) => fail(s"Operation receiver $receiver has non-capability type ${show(other)}")
              case None => env.capability(receiver)
            }
            val op = env.capabilities.getOrElse(identity, fail(s"Unknown capability $receiver"))
              .find(_.name == name).getOrElse(fail(s"Unknown operation $receiver.$name"))
            Contracts.erase(op.result)
        }
      }
      def same(results: List[Type]): Type = results match {
        case first :: rest if rest.forall(TypeEquality.same(_, first)) => first
        case _ => unsupported()
      }
      term match {
        case Term.Return(v) => result(v)
        case Term.Let(name, binding, body) =>
          val t = projectedValue(binding, env, headers)
          projectedValue(body, env.bind(name, t), headers - name)
        case Term.Apply(f, _) => result(f) match {
          case Type.Function(_, r, _, _, _, _) => Contracts.erase(r.value)
          case Type.FixedFunction(_, r, _, _, _) => Contracts.erase(r.value)
          case Type.Continuation(_, r, _, _, _) => Contracts.erase(r.value)
          case _ => unsupported()
        }
        case Term.Perform(c, op, _) => operation(c, op)
        case Term.AnnotatedPerform(c, op, _, _, _) => operation(c, op)
        case Term.SingleAnnotatedPerform(c, op, _, _) => operation(c, op)
        case Term.Ascribe(_, c) => Contracts.erase(resolve(c, env).value)
        case Term.Handle(_, c, _, _, _, _) => Contracts.erase(resolve(c, env).value)
        case Term.InferredHandle(c, ops, b, x, ret) => handlerResultValue(c, ops, b, x, ret, env, headers)
        case Term.ExplicitReturn(b) => projectedValue(b, env, headers)
        case Term.If(_, a, b) => same(List(projectedValue(a, env, headers), projectedValue(b, env, headers)))
        case Term.Assert(_) => Type.Unit
        case Term.TypeAlias(n, t, b) =>
          val scope = env.copy(aliases = env.aliases.updated(n, resolve(t, env)), types = env.types - n, annotations = env.annotations - n)
          projectedValue(b, scope, headers)
        case Term.AnnotationAlias(n, a, b) =>
          val (alias, residuals) = resolveArgument(a, env)
          projectedValue(b, env.withAnnotation(n, alias, residuals), headers)
        case Term.LetRec(n, p, a, r, _, b, anchor, name) =>
          val argument = resolve(a, env)
          val caller = Option.when(anchor.isEmpty)(Caller(fresh("projectedCaller"), fresh("projectedDistance")))
          val region = caller.fold(env.region)(_.region)
          val answer = resolve(r, env.copy(region = region, annotationDefaultRegion = Some(env.region))
            .withCaller(name, region).bind(p, argument))
          val arrow = if anchor.nonEmpty then Type.FixedFunction(argument, answer, region = Some(env.region))
            else Type.Function(argument, answer, region = Some(env.region), caller = caller)
          projectedValue(b, env.bind(n, Contracts.erase(arrow)), headers - n)
        case Term.Match(_, cases) => same(cases.map { c =>
          val fields = constructors.getOrElse(c.constructor, fail(s"Unknown constructor ${c.constructor}"))._2.fields
          require(fields.size == c.parameters.size, "Wrong number of pattern fields")
          val scope = c.parameters.zip(fields).foldLeft(env) { case (scope, (n, (_, t))) => scope.bind(n, Contracts.erase(t)) }
          projectedValue(c.body, scope, headers -- c.parameters)
        })
      }
    }

    private def inferOperation(receiver: String, operation: String, argument: Value,
        evidence: Option[Proof], explicitContext: Option[AnswerContext], env: Environment,
        inherited: Option[Computation]): CheckedTerm = {
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
      val reconstruction = evidence.fold(reconstruct(env.region, cap, env))(resolveProof(_, env))
      require(reconstruction.witness.constraint == Constraint(env.region, cap), "Operation evidence has incorrect region endpoints")
      val context = try {
        explicitContext match {
          case Some(k) =>
            val resolved = resolveContext(k, env)
            require(Evidence.sameDistance(Annotations.depth(resolved, env.kinds), reconstruction.witness.distance),
              s"ATC depth differs from evidence distance: context ${Annotations.depth(resolved, env.kinds)}, evidence ${reconstruction.witness.distance}")
            resolved
          case None =>
            val answer = inherited.getOrElse(fail(s"$cap.$operation needs an inherited final answer type or an explicit ATC"))
            Evidence.atc(answer, op.finalAnswer, reconstruction.witness.distance, answerSubtype(_, _, env))
        }
      } catch {
        case error: Diagnostic =>
          observe.foreach(_(OperationInference(cap, operation, reconstruction, Left(error))))
          throw error
      }
      observe.foreach(_(OperationInference(cap, operation, reconstruction, Right(context))))
      val initial = context.plug(op.initialAnswer)
      val answer = if explicitContext.nonEmpty then context.plug(op.finalAnswer) else inherited.get
      val inferred = Computation(op.result, Effect.Change(initial, answer), resultIdentity)
      val result = if explicitContext.nonEmpty && inherited.nonEmpty then adapt(inferred, inherited, env) else inferred
      val target = if env.values.contains(receiver) then receiver else cap
      val checked = CheckedTerm(Term.Perform(target, operation, argument), result,
        arg.captures + cap, List(arg), operationArgument = Some(op.argument),
        residuals = reconstruction.residuals, evidence = List(reconstruction.witness),
        operationTyping = Some(OperationTyping(op, context, inferred)))
      resultIdentity match {
        case None => symbolicTerm(checked, env)
        case Some(id) =>
          val sort = Contracts.sort(op.result).getOrElse(fail("Dependent operation results require a logical base type"))
          checked.copy(logic = env.logic.bind(id, sort).assume(Contracts.member(op.result, Expr.Variable(id))), symbolic = Some(Expr.Variable(id)))
      }
    }

    private def inferHandler(cap: String, finalType: Computation, operations: List[Operation], body: Term,
                             returned: String, onReturn: Term, env: Environment,
                             inherited: Option[Computation]): CheckedTerm = {
      val identity = fresh(cap)
      require(operations.map(_.name).distinct.size == operations.size, "Duplicate operation in handler")
      val ops = operations.map { op =>
        // Result-only clauses preserve the enclosing handler's answer. This is
        // surface annotation inference; the ordinary clause and body checks
        // below still validate the resulting explicit operation contract.
        val inferred = Option.when(op.answers.isEmpty)(finalType)
        val annotated = op.copy(answers = op.answers.orElse(Some((finalType, finalType))))
        val signature = resolveSignature(annotated.signature.copy(parameter = Some(op.parameter)), env, inferred,
          declaredSignature(op, env), op.argumentAnnotated, op.resultAnnotated, op.argumentAnnotations)
        op.copy(argument = signature.argument, result = signature.result,
          answers = Some((signature.initialAnswer, signature.finalAnswer)),
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

package effekt.atm

import effekt.{lifted as L}
import effekt.core.Id
import Syntax.{Type, Computation, Signature, Diagnostic}
import Core.{ValueNode as V, Node as N}

/** Direct translation to historical Lifted. Witnesses become real EV arguments;
 * no surface parser, native typer, lift inference or answer-tag encoding is used.
 */
object LiftedLowering {
  case class Annotation(statement: L.Stmt, computation: Computation, region: String, runtimeType: L.ValueType)
  final class Program private[LiftedLowering](val core: Core.Program, val module: L.ModuleDecl,
      val main: Id, val annotations: List[Annotation]) {
    def validate(): Unit = {
      Core.validate(core.body, core.datatypes)
      LiftedValidation.check(module)
      annotations.foreach { a =>
        if !LiftedValidation.same(a.statement.tpe, a.runtimeType) then
          throw Diagnostic(s"Lifted result disagrees with core annotation ${Syntax.show(a.computation)} at ${a.region}")
      }
    }
    def show: String = L.PrettyPrinter.format(module).layout
  }
  def apply(program: Checker.CheckedProgram): Program = apply(Core.elaborate(program))
  def apply(program: Core.Program): Program = {
    Core.validate(program.body, program.datatypes)
    val result = new Generator().program(program)
    result.validate()
    result
  }

  private case class InterfaceInfo(id: Id, parameters: List[Id], properties: Map[String, Id])
  private case class Capability(block: L.Block, operations: List[Signature], info: InterfaceInfo)
  private case class Env(values: Map[String, L.Expr] = Map.empty, caps: Map[String, Capability] = Map.empty,
      types: Map[String, L.ValueType] = Map.empty, evidence: Map[String, L.EvidenceSymbol] = Map.empty) {
    def bind(name: String, v: L.Expr): Env = copy(values = values.updated(name, v))
  }
  private class Generator {
    private val declarations = scala.collection.mutable.ListBuffer.empty[L.Declaration]
    private val externs = scala.collection.mutable.ListBuffer.empty[L.Extern]
    private val annotations = scala.collection.mutable.ListBuffer.empty[Annotation]
    private val interfaces = scala.collection.mutable.Map.empty[List[String], InterfaceInfo]
    private var dataDeclarations = Map.empty[String, Syntax.DataDeclaration]
    private val equalityFunctions = scala.collection.mutable.Map.empty[String, Id]
    private val definitions = scala.collection.mutable.ListBuffer.empty[L.Definition]
    private var dataTypes = Map.empty[String, L.ValueType.Data]
    private var constructors = Map.empty[String, Id]
    private val tokens = scala.collection.mutable.Map.empty[String, (L.ValueType.Data, Id)]
    private val primitives = scala.collection.mutable.Map.empty[(String, List[L.ValueType], L.ValueType), L.Block]
    private val pair = Id("AtmPair")
    private val pairCtor = Id("AtmPairValue")
    private val first = Id("atmFirst")
    private val second = Id("atmSecond")
    private val pairA = Id("A"); private val pairB = Id("B")
    declarations += L.Declaration.Data(pair, List(pairA, pairB), List(L.Constructor(pairCtor,
      List(L.Field(first, L.ValueType.Var(pairA)), L.Field(second, L.ValueType.Var(pairB))))))

    def program(p: Core.Program): Program = {
      dataDeclarations = p.datatypes.map(d => d.name -> d).toMap
      dataTypes = p.datatypes.map(d => d.name -> L.ValueType.Data(Id("AtmData" + d.name), Nil).asInstanceOf[L.ValueType.Data]).toMap
      constructors = p.datatypes.flatMap(_.constructors.map(c => c.name -> Id("AtmCtor" + c.name))).toMap
      p.datatypes.foreach { d =>
        declarations += L.Declaration.Data(dataTypes(d.name).name, Nil, d.constructors.map(c =>
          L.Constructor(constructors(c.name), c.fields.map((n, t) => L.Field(Id("AtmField" + c.name + n), target(t, Env()))))))
      }
      val main = Id("atmMain")
      val ev = new L.EvidenceSymbol()
      val body = term(p.body, Env())
      val block = L.Block.BlockLit(Nil, List(L.Param.EvidenceParam(ev)), body)
      val module = L.ModuleDecl("atm", Nil, declarations.toList, externs.toList,
        definitions.toList :+ L.Definition.Def(main, block), List(main))
      new Program(p, module, main, annotations.toList)
    }

    private def function(args: List[L.ValueType], result: L.ValueType, evidence: Int = 0,
        blocks: List[L.BlockType] = Nil, types: List[Id] = Nil): L.BlockType.Function =
      L.BlockType.Function(types, List.fill(evidence)(L.EvidenceType()), args, blocks, result)

    private def token(name: String): (L.ValueType.Data, Id) = tokens.getOrElseUpdate(name, {
      val id = Id("AtmToken" + name); val ctor = Id("AtmMake" + name)
      declarations += L.Declaration.Data(id, Nil, List(L.Constructor(ctor, Nil)))
      (L.ValueType.Data(id, Nil), ctor)
    })

    private def interface(ops: List[Signature], env: Env): (L.BlockType.Interface, InterfaceInfo) = {
      val sorted = ops.sortBy(_.name)
      val info = interfaces.getOrElseUpdate(sorted.map(_.name), {
        val id = Id("AtmOperations")
        val parameters = sorted.flatMap(_ => List(Id("P"), Id("R")))
        val properties = sorted.map(op => op.name -> Id("atm_" + op.name)).toMap
        declarations += L.Declaration.Interface(id, parameters, sorted.zipWithIndex.map { (op, i) =>
          L.Property(properties(op.name), function(List(L.ValueType.Var(parameters(2 * i))), L.ValueType.Var(parameters(2 * i + 1)), 1))
        })
        InterfaceInfo(id, parameters, properties)
      })
      (L.BlockType.Interface(info.id, sorted.flatMap(o => List(target(o.argument, env), target(o.result, env)))), info)
    }

    private def target(t: Type, env: Env): L.ValueType = t match {
      case Type.Refined(_, base, _) => target(base, env)
      case Type.Int => L.Type.TInt
      case Type.Bool => L.Type.TBoolean
      case Type.Unit => L.Type.TUnit
      case Type.Token(n) => token(n)._1
      case Type.Data(n) => dataTypes(n)
      case Type.Variable(n) => env.types.getOrElse(n, throw Diagnostic("Unbound Lifted type variable " + n))
      case Type.Pair(a, b) => L.ValueType.Data(pair, List(target(a, env), target(b, env)))
      case Type.Function(a, c, _, _, _) => L.ValueType.Boxed(function(List(target(a, env)), target(c.value, env), 1))
      case Type.Continuation(a, c, _, _, _) => L.ValueType.Boxed(function(List(target(a, env)), target(c.value, env)))
      case Type.LocalFunction(a, c, _, _, _) => L.ValueType.Boxed(function(List(target(a, env)), target(c.value, env)))
      case Type.Capability(r) => L.ValueType.Boxed(env.caps.getOrElse(r, throw Diagnostic("Unbound Lifted capability " + r)).block.tpe)
      case Type.AbstractResult(Syntax.ComputationForm.Variable(n)) =>
        env.types.getOrElse(n, throw Diagnostic("Unbound Lifted computation parameter " + n))
      case _: Type.AbstractResult =>
        throw Diagnostic("Lifted lowering requires a known value projection for an abstract ATC application", Syntax.DiagnosticKind.Unsupported)
      case Type.KindPoly(n, Annotations.Kind.Computation, body) =>
        val id = Id("AtmComputationResult")
        L.ValueType.Boxed(function(Nil, target(body, env.copy(types = env.types.updated(n, L.ValueType.Var(id)))), types = List(id)))
      case Type.KindPoly(_, _: Annotations.Kind.Evidence, body) =>
        L.ValueType.Boxed(function(Nil, target(body, env), 1))
      case Type.KindPoly(_, _, body) => target(body, env)
      case Type.TypePoly(n, body) =>
        val id = Id("AtmType")
        L.ValueType.Boxed(function(Nil, target(body, env.copy(types = env.types.updated(n, L.ValueType.Var(id)))), types = List(id)))
      case Type.PredicatePoly(_, _, body) => target(body, env)
      case Type.EffectPoly(_, body, _) => target(body, env)
      case Type.CapabilityPoly(r, ops, body, _, _, obligations) =>
        val (tpe, info) = interface(ops, env)
        val cap = Capability(L.Block.BlockVar(Id("capType"), tpe), ops, info)
        L.ValueType.Boxed(function(Nil, target(body, env.copy(caps = env.caps.updated(r, cap))),
          obligations.size, List(tpe)))
    }

    private def proof(w: Evidence.Witness, env: Env): L.Evidence = {
      def lifts(w: Evidence.Witness): List[L.Lift] = w match {
        case Evidence.Witness.Refl(_) => Nil
        case Evidence.Witness.Use(b) => List(L.Lift.Var(env.evidence.getOrElse(b.name,
          throw Diagnostic("Unbound Lifted evidence " + b.name))))
        case Evidence.Witness.Compose(a, b) => lifts(a) ++ lifts(b)
      }
      L.Evidence(lifts(w))
    }

    private def primitive(name: String, args: List[L.Expr], result: L.ValueType): L.Expr = {
      val block = primitives.getOrElseUpdate((name, args.map(_.tpe), result), {
        val id = Id("atm_" + name)
        val params: List[L.Param.ValueParam] = args.map(a => L.Param.ValueParam(Id("arg"), a.tpe))
        val vars = params.map(p => L.Expr.ValueVar(p.id, p.tpe))
        val operator = Map("add" -> "+", "sub" -> "-", "mul" -> "*", "div" -> "quotient", "mod" -> "remainder",
          "lt" -> "<", "le" -> "<=", "gt" -> ">", "ge" -> ">=", "eq" -> "equal?", "ne" -> "equal?",
          "zero" -> "zero?", "not" -> "not", "and" -> "and", "or" -> "or")(name)
        val prefix = (if name == "ne" then "(not (" else "(") + operator + " "
        val suffix = if name == "ne" then "))" else ")"
        val template = effekt.Template((prefix :: List.fill(args.size - 1)(" ")) :+ suffix, vars)
        externs += L.Extern.Def(id, Nil, params, result,
          L.ExternBody.StringExternBody(effekt.source.FeatureFlag.NamedFeatureFlag("chezLift"), template))
        L.Block.BlockVar(id, function(args.map(_.tpe), result))
      })
      L.Expr.PureApp(block, Nil, args)
    }

    // Chez's opaque records do not have structural equal? semantics. Generate
    // checked Lifted matches instead of depending on the host record identity.
    private def equality(t: Type, a: L.Expr, b: L.Expr, env: Env): L.Expr = Contracts.base(t) match {
      case Type.Data(n) =>
        val id = equalityFunctions.get(n).getOrElse {
          val id = Id("atmDataEqual" + n)
          equalityFunctions(n) = id // bind before generating recursive calls
          val data = dataDeclarations(n)
          val x: L.Param.ValueParam = L.Param.ValueParam(Id("left"), dataTypes(n))
          val y: L.Param.ValueParam = L.Param.ValueParam(Id("right"), dataTypes(n))
          val clauses = data.constructors.map { c =>
            val xs = c.fields.map[L.Param.ValueParam]((_, t) => L.Param.ValueParam(Id("field"), target(t, env)))
            val inner = data.constructors.map { d =>
              val ys = d.fields.map[L.Param.ValueParam]((_, t) => L.Param.ValueParam(Id("field"), target(t, env)))
              val result = if c.name != d.name then L.Stmt.Return(L.Expr.Literal(false, L.Type.TBoolean))
                else c.fields.zip(xs.zip(ys)).foldRight[L.Stmt](L.Stmt.Return(L.Expr.Literal(true, L.Type.TBoolean))) {
                  case (((_, t), (x, y)), rest) => L.Stmt.If(equality(t, L.Expr.ValueVar(x.id, x.tpe), L.Expr.ValueVar(y.id, y.tpe), env),
                    rest, L.Stmt.Return(L.Expr.Literal(false, L.Type.TBoolean)))
                }
              val block: L.Block.BlockLit = L.Block.BlockLit(Nil, ys, result)
              constructors(d.name) -> block
            }
            val block: L.Block.BlockLit = L.Block.BlockLit(Nil, xs, L.Stmt.Match(L.Expr.ValueVar(y.id, y.tpe), inner, None))
            constructors(c.name) -> block
          }
          definitions += L.Definition.Def(id, L.Block.BlockLit(Nil, List(x, y), L.Stmt.Match(L.Expr.ValueVar(x.id, x.tpe), clauses, None)))
          id
        }
        L.Expr.Run(L.Stmt.App(L.Block.BlockVar(id, function(List(dataTypes(n), dataTypes(n)), L.Type.TBoolean)), Nil, List(a, b)))
      case Type.Pair(x, y) =>
        L.Expr.Run(L.Stmt.If(equality(x, L.Expr.Select(a, first, target(x, env)), L.Expr.Select(b, first, target(x, env)), env),
          L.Stmt.Return(equality(y, L.Expr.Select(a, second, target(y, env)), L.Expr.Select(b, second, target(y, env)), env)),
          L.Stmt.Return(L.Expr.Literal(false, L.Type.TBoolean))))
      case Type.Unit => L.Expr.Literal(true, L.Type.TBoolean)
      case _ => primitive("eq", List(a, b), L.Type.TBoolean)
    }

    private def value(v: Core.Value, env: Env): L.Expr = v.node match {
      case V.Integer(n) => L.Expr.Literal(n, L.Type.TInt)
      case V.Boolean(b) => L.Expr.Literal(b, L.Type.TBoolean)
      case V.Unit => L.Expr.Literal((), L.Type.TUnit)
      case V.Token(n) => val (tpe, ctor) = token(n); L.Expr.Make(tpe, ctor, Nil)
      case V.Constructor(n, args) => L.Expr.Make(target(v.tpe, env).asInstanceOf[L.ValueType.Data], constructors(n), args.map(value(_, env)))
      case V.Variable(n) => env.values.getOrElse(n, throw Diagnostic("Unbound Lifted value " + n))
      case V.Capability(r) => L.Expr.Box(env.caps(r).block)
      case V.Primitive("pair", args) => L.Expr.Make(target(v.tpe, env).asInstanceOf[L.ValueType.Data], pairCtor, args.map(value(_, env)))
      case V.Primitive("fst", args) => L.Expr.Select(value(args.head, env), first, target(v.tpe, env))
      case V.Primitive("snd", args) => L.Expr.Select(value(args.head, env), second, target(v.tpe, env))
      case V.Primitive(op @ ("eq" | "ne"), args) =>
        val equal = equality(args.head.tpe, value(args.head, env), value(args(1), env), env)
        if op == "eq" then equal else primitive("not", List(equal), L.Type.TBoolean)
      case V.Primitive(name, args) => primitive(name, args.map(value(_, env)), target(v.tpe, env))
      case V.LocalLambda(parameter, _, body) =>
        val local = v.tpe.asInstanceOf[Type.LocalFunction]
        val id = Id("atmLocalArg"); val argType = target(local.argument, env)
        L.Expr.Box(L.Block.BlockLit(Nil, List(L.Param.ValueParam(id, argType)),
          term(body, env.bind(parameter, L.Expr.ValueVar(id, argType)))))
      case V.Lambda(parameter, a, body) =>
        val Type.Function(arg, _, _, _, _) = v.tpe: @unchecked
        val id = Id("atmArg"); val ev = new L.EvidenceSymbol()
        val argType = target(arg, env)
        val inner = env.bind(parameter, L.Expr.ValueVar(id, argType)).copy(evidence = env.evidence.updated(a.call.name, ev))
        L.Expr.Box(L.Block.BlockLit(Nil, List(L.Param.EvidenceParam(ev), L.Param.ValueParam(id, argType)), term(body, inner)))
      case V.CapabilityLambda(r, ops, requirements, body) =>
        val (capType, info) = interface(ops, env)
        val capId = Id("atmCap")
        val evs = requirements.map(_ => new L.EvidenceSymbol())
        val inner = env.copy(caps = env.caps.updated(r, Capability(L.Block.BlockVar(capId, capType), ops, info)),
          evidence = env.evidence ++ requirements.zip(evs).map((b, id) => b.name -> id))
        L.Expr.Box(L.Block.BlockLit(Nil, evs.map(L.Param.EvidenceParam.apply) :+ L.Param.BlockParam(capId, capType),
          L.Stmt.Return(value(body, inner))))
      case V.Instantiate(f, cap, evs) =>
        L.Expr.Run(L.Stmt.App(L.Block.Unbox(value(f, env)), Nil,
          evs.map(proof(_, env)) :+ L.Block.Unbox(value(cap, env))))
      case V.KindLambda(p, Annotations.Kind.Computation, body) =>
        val id = Id("AtmComputationResult")
        L.Expr.Box(L.Block.BlockLit(List(id), Nil, L.Stmt.Return(value(body, env.copy(types = env.types.updated(p, L.ValueType.Var(id)))))))
      case V.KindLambda(p, _: Annotations.Kind.Evidence, body) =>
        val ev = new L.EvidenceSymbol()
        L.Expr.Box(L.Block.BlockLit(Nil, List(L.Param.EvidenceParam(ev)),
          L.Stmt.Return(value(body, env.copy(evidence = env.evidence.updated(p, ev))))))
      case V.KindLambda(_, _, body) => value(body, env)
      case V.InstantiateKind(f, Annotations.Argument.Computation(c)) =>
        L.Expr.Run(L.Stmt.App(L.Block.Unbox(value(f, env)), List(target(c.value, env)), Nil))
      case V.InstantiateKind(f, Annotations.Argument.Evidence(Annotations.Proof.Checked(w))) =>
        L.Expr.Run(L.Stmt.App(L.Block.Unbox(value(f, env)), Nil, List(proof(w, env))))
      case V.InstantiateKind(f, _) => value(f, env)
      case V.TypeLambda(p, body) =>
        val id = Id("AtmType")
        L.Expr.Box(L.Block.BlockLit(List(id), Nil, L.Stmt.Return(value(body, env.copy(types = env.types.updated(p, L.ValueType.Var(id)))))))
      case V.InstantiateType(f, arg) => L.Expr.Run(L.Stmt.App(L.Block.Unbox(value(f, env)), List(target(arg, env)), Nil))
      case V.ProofAbstraction(body) => value(body, env)
      case V.ProofApplication(body) => value(body, env)
    }

    private def term(t: Core.Term, env: Env): L.Stmt = {
      val result = t.node match {
        case N.Return(v) => L.Stmt.Return(value(v, env))
        case N.Assert => L.Stmt.Return(L.Expr.Literal((), L.Type.TUnit))
        case N.Ascribe(body) => term(body, env)
        case N.Let(name, binding, body) =>
          val id = Id("atmVal"); val bound = term(binding, env)
          L.Stmt.Val(id, bound, term(body, env.bind(name, L.Expr.ValueVar(id, bound.tpe))))
        case N.LetRec(name, f, body) =>
          val id = Id("atmRecursive")
          val L.ValueType.Boxed(tpe) = target(f.tpe, env): @unchecked
          val inner = env.bind(name, L.Expr.Box(L.Block.BlockVar(id, tpe)))
          val L.Expr.Box(block) = value(f, inner): @unchecked
          L.Stmt.Scope(List(L.Definition.Def(id, block)), term(body, inner))
        case N.Apply(f, a, evs) =>
          L.Stmt.App(L.Block.Unbox(value(f, env)), Nil, evs.map(proof(_, env)) :+ value(a, env))
        case N.If(c, a, b) => L.Stmt.If(value(c, env), term(a, env), term(b, env))
        case N.Match(v, cases) =>
          val clauses = cases.map { c =>
            val params = c.bindings.map[L.Param.ValueParam]((n, t) => L.Param.ValueParam(Id("atmPattern"), target(t, env)))
            val scope = c.bindings.zip(params).foldLeft(env) { case (e, ((n, _), p)) =>
              e.bind(n, L.Expr.ValueVar(p.id, p.tpe))
            }
            val block: L.Block.BlockLit = L.Block.BlockLit(Nil, params, term(c.body, scope))
            constructors(c.constructor) -> block
          }
          L.Stmt.Match(value(v, env), clauses, None)
        case N.Perform(receiver, op, a, ev, typing) =>
          val region = ev.constraint.to
          val cap = env.caps.getOrElse(region, throw Diagnostic("Missing operation capability " + region))
          val block = env.values.get(receiver).map(L.Block.Unbox.apply).getOrElse(cap.block)
          val sig = cap.operations.find(_.name == op).get
          val tpe = function(List(target(sig.argument, env)), target(sig.result, env), 1)
          L.Stmt.App(L.Block.Member(block, cap.info.properties(op), tpe), Nil, List(proof(ev, env), value(a, env)))
        case N.Handle(region, edge, body, returned, onReturn, operations) =>
          val ops = operations.map(_.signature)
          val (capType, info) = interface(ops, env)
          val capId = Id("atmHandler"); val edgeId = new L.EvidenceSymbol()
          val inner = env.copy(caps = env.caps.updated(region, Capability(L.Block.BlockVar(capId, capType), ops, info)),
            evidence = env.evidence.updated(edge.name, edgeId))
          val handled: L.Block.BlockLit = L.Block.BlockLit(Nil, List(L.Param.EvidenceParam(edgeId), L.Param.BlockParam(capId, capType)), term(body, inner))
          val retId = Id("atmReturned")
          val ret: L.Block.BlockLit = L.Block.BlockLit(Nil, List(L.Param.ValueParam(retId, target(body.tpe.value, env))),
            term(onReturn, env.bind(returned, L.Expr.ValueVar(retId, target(body.tpe.value, env)))))
          val clauses = operations.sortBy(_.signature.name).map { op =>
            val sig = op.signature
            val arg = Id("atmOpArg"); val resume = Id("atmResume"); val argType = target(sig.argument, env)
            val resumeArg = target(sig.result, env); val resumeResult = target(sig.initialAnswer.value, env)
            val ev = new L.EvidenceSymbol()
            val resumeType = function(List(resumeArg), resumeResult, 1)
            val x = Id("atmResumeArg")
            // A_KApp is monomorphic in its ambient region. Historical Shift
            // expects an EV-accepting resumption, so this adapter supplies Here.
            val plainResume = L.Expr.Box(L.Block.BlockLit(Nil, List(L.Param.ValueParam(x, resumeArg)),
              L.Stmt.App(L.Block.BlockVar(resume, resumeType), Nil, List(L.Here(), L.Expr.ValueVar(x, resumeArg)))))
            val clause = term(op.body, env.bind(op.parameter, L.Expr.ValueVar(arg, argType)).bind(op.continuation, plainResume))
            L.Operation(info.properties(sig.name), L.Block.BlockLit(Nil,
              List(L.Param.EvidenceParam(ev), L.Param.ValueParam(arg, argType)),
              L.Stmt.Shift(L.Evidence(List(L.Lift.Var(ev))), L.Block.BlockLit(Nil, List(L.Param.BlockParam(resume, resumeType)), clause))))
          }
          L.Stmt.Handle(handled, List(L.Implementation(capType, clauses)), ret, target(t.tpe.value, env))
      }
      annotations += Annotation(result, t.tpe, t.region, target(t.tpe.value, env))
      result
    }
  }
}

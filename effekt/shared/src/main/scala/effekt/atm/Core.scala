package effekt.atm

import Syntax.{Type, Computation, Signature, Diagnostic, DataDeclaration}
import Evidence.*

/** Explicit region/evidence core. No source terms, implicit applications or
 * unresolved obligations occur in this representation. Refinements and answer
 * transitions remain on every node until runtime lowering.
 */
object Core {
  case class Value(node: ValueNode, tpe: Type)
  enum ValueNode {
    case Integer(value: BigInt)
    case Boolean(value: scala.Boolean)
    case Unit
    case Token(name: String)
    case Constructor(name: String, arguments: List[Value])
    case Variable(name: String)
    case Capability(region: String)
    case Primitive(name: String, arguments: List[Value])
    case Lambda(parameter: String, abstraction: FunctionAbstraction, body: Term)
    case FixedLambda(parameter: String, region: String, body: Term)
    case CapabilityLambda(region: String, operations: List[Signature], requirements: List[Binding], body: Value)
    case Instantiate(function: Value, capability: Value, evidence: List[Witness])
    case KindLambda(parameter: String, kind: Annotations.Kind, body: Value)
    case InstantiateKind(function: Value, argument: Annotations.Argument)
    case TypeLambda(parameter: String, body: Value)
    case InstantiateType(function: Value, argument: Type)
    case ProofAbstraction(body: Value)
    case ProofApplication(body: Value)
  }
  case class Term(node: Node, tpe: Computation, region: String)
  case class Operation(signature: Signature, parameter: String, continuation: String, body: Term)
  case class MatchCase(constructor: String, bindings: List[(String, Type)], body: Term)
  enum Node {
    case Return(value: Value)
    case Assert
    case Let(name: String, binding: Term, body: Term)
    case LetRec(name: String, function: Value, body: Term)
    case Apply(function: Value, argument: Value, evidence: List[Witness], contexts: List[AnswerContext] = Nil)
    case If(condition: Value, thn: Term, els: Term)
    case Match(scrutinee: Value, cases: List[MatchCase])
    case Perform(receiver: String, operation: String, argument: Value, evidence: Witness, typing: Checker.OperationTyping)
    case Handle(region: String, edge: Binding, body: Term, returned: String, onReturn: Term, operations: List[Operation])
    case Ascribe(body: Term)
  }
  final class Program private[Core](val body: Term, val datatypes: List[DataDeclaration], val measures: List[Logic.Measure])

  def elaborate(program: Checker.CheckedProgram): Program = {
    Elaboration.validate(program.body)
    if program.body.residuals.nonEmpty then throw Diagnostic("Core elaboration has undischarged residual obligations")
    def value(v: Checker.CheckedValue, region: String): Value = {
      import Syntax.Value as S
      val node = v.source match {
        case S.Integer(n) => ValueNode.Integer(n)
        case S.Boolean(b) => ValueNode.Boolean(b)
        case S.Unit => ValueNode.Unit
        case S.Token(n) => ValueNode.Token(n)
        case S.Constructor(n, _) => ValueNode.Constructor(n, v.children.map(value(_, region)))
        case S.Variable(n) => ValueNode.Variable(n)
        case S.Capability(r) => ValueNode.Capability(r)
        case S.Primitive(n, _) => ValueNode.Primitive(n, v.children.map(value(_, region)))
        case S.FixedLambda(p, _, _, _, _) =>
          val target = v.tpe.asInstanceOf[Type.FixedFunction].region.get
          ValueNode.FixedLambda(p, target, term(v.body.get, target))
        case S.Lambda(p, _, _, _, _) => lambda(v, p, region)
        case S.ContextLambda(p, _, _, _, _, _) => lambda(v, p, region)
        case S.InferredLambda(p, _, _, _) => lambda(v, p, region)
        case S.UnannotatedLambda(p, _, _, _) => lambda(v, p, region)
        case S.CapabilityLambda(r, ops, _, _) => ValueNode.CapabilityLambda(r, ops, v.quantified, value(v.children.head, region))
        case S.Instantiate(_, _) => ValueNode.Instantiate(value(v.children.head, region), value(v.children(1), region), v.evidence)
        case S.KindLambda(p, k, _) => ValueNode.KindLambda(p, k, value(v.children.head, region))
        case S.InstantiateKind(_, a) =>
          val actual = a match {
            case _: Annotations.Argument.Evidence => Annotations.Argument.Evidence(Annotations.Proof.Checked(v.evidence.head))
            case _ => a
          }
          ValueNode.InstantiateKind(value(v.children.head, region), actual)
        case S.TypeLambda(p, _) => ValueNode.TypeLambda(p, value(v.children.head, region))
        case S.InstantiateType(_, a) => ValueNode.InstantiateType(value(v.children.head, region), a)
        case _: S.PredicateLambda | _: S.EffectLambda => ValueNode.ProofAbstraction(value(v.children.head, region))
        case _: S.InstantiatePredicate | _: S.InstantiateEffect => ValueNode.ProofApplication(value(v.children.head, region))
        case _: S.InstantiatePolymorphic => throw Diagnostic("Unresolved polymorphic argument reached Core")
      }
      Value(node, v.tpe)
    }
    def lambda(v: Checker.CheckedValue, parameter: String, region: String): ValueNode = {
      val a = v.abstraction.getOrElse(throw Diagnostic("Core lambda lacks its region/evidence abstraction"))
      ValueNode.Lambda(parameter, a, term(v.body.get, a.region))
    }
    def term(t: Checker.CheckedTerm, region: String): Term = {
      import Syntax.Term as S
      val node = t.source match {
        case _: S.Return => Node.Return(value(t.values.head, region))
        case _: S.Assert => Node.Assert
        case S.Let(n, _, _) => Node.Let(n, term(t.children.head, region), term(t.children(1), region))
        case S.LetRec(n, _, _, _, _, _, _, _) => Node.LetRec(n, value(t.values.head, region), term(t.children(1), region))
        case _: S.Apply => Node.Apply(value(t.values.head, region), value(t.values(1), region), t.evidence, t.callContexts)
        case _: S.If => Node.If(value(t.values.head, region), term(t.children.head, region), term(t.children(1), region))
        case S.Match(_, cases) => Node.Match(value(t.values.head, region),
          cases.zip(t.patternBindings.zip(t.children)).map { case (c, (fields, body)) =>
            MatchCase(c.constructor, fields, term(body, region))
          })
        case S.Perform(c, op, _) => Node.Perform(c, op, value(t.values.head, region), t.evidence.head,
          t.operationTyping.getOrElse(throw Diagnostic("Core operation lacks its inferred ATC")))
        case S.Handle(r, _, _, _, ret, _) => Node.Handle(r, t.handlerEvidence.get,
          term(t.children.head, r), ret, term(t.children(1), region), t.operations.map(o =>
            Operation(o.source.signature, o.source.parameter, o.source.continuation, term(o.body, region))))
        case _: S.InferredHandle => throw Diagnostic("Core: unresolved handler result annotation")
        case _: S.ExplicitReturn => throw Diagnostic("Core: unresolved explicit return clause")
        case _: S.AnnotatedPerform | _: S.SingleAnnotatedPerform => throw Diagnostic("Core: unresolved operation annotation")
        case _: S.Ascribe | _: S.TypeAlias | _: S.AnnotationAlias => Node.Ascribe(term(t.children.head, region))
      }
      Term(node, t.tpe, region)
    }
    val root = term(program.body, Top)
    val datatypes = program.datatypes
    validate(root, datatypes)
    new Program(root, datatypes, program.source.measures)
  }

  /** Check the region/evidence and ATC certificates independently of inference.
   * Refinement entailments have already been checked by Checker; this is not a
   * second implementation of all declarative subtyping rules.
   */
  def validate(root: Term, datatypes: List[DataDeclaration] = Nil): Unit = {
    val constructors = Datatypes.constructors(Datatypes.validate(datatypes))
    def require(ok: Boolean, message: String): Unit = if !ok then throw Diagnostic("Core: " + message)
    def proof(w: Witness, bs: Map[String, Binding], rs: Set[String]): Unit = {
      require(w.constraint.free.subsetOf(rs), "unbound evidence region")
      w match {
        case Witness.Refl(_) => ()
        case Witness.Use(b) => require(bs.get(b.name).contains(b), "unbound or mistyped evidence " + b.name)
        case Witness.Compose(a, b) => proof(a, bs, rs); proof(b, bs, rs)
      }
    }
    def value(v: Value, bs: Map[String, Binding], rs: Set[String], region: String, vs: Map[String, Type], cs: Map[String, List[Signature]], ks: Map[String, Annotations.Kind]): Unit = v.node match {
      case ValueNode.FixedLambda(parameter, target, body) =>
        val tpe = v.tpe match {
          case t: Type.FixedFunction => t
          case _ => throw Diagnostic("Core: local lambda has a non-local arrow")
        }
        require(tpe.region.contains(target) && (target == Top || rs(target)), "local lambda has an unbound or mismatched region")
        require(TypeEquality.same(body.tpe, tpe.result), "local lambda body/result contract mismatch")
        term(body, bs, rs, target, vs.updated(parameter, tpe.argument), cs, ks)
      case ValueNode.Lambda(parameter, a, body) =>
        require(a.call.constraint.from == a.region, "malformed function evidence binder")
        v.tpe match {
          case Type.Function(_, _, _, Some(target), _, _) => require(a.call.constraint.to == target, "function scope differs from evidence binder")
          case _ => throw Diagnostic("Core: lambda has no function scope")
        }
        require((a.call.constraint.free - a.region).subsetOf(rs), "unbound function region")
        val function = v.tpe.asInstanceOf[Type.Function]
        val argument = function.argument
        // The implicit caller and call-ATC telescope does not bind the domain.
        Annotations.validateArgument(Annotations.Argument.Computation(Computation(argument)), ks, rs)
        require(TypeEquality.same(body.tpe, FunctionTypes.result(function, a.region, a.call.distance)),
          "function body does not implement its caller-indexed result")
        require(!rs(a.region) && !bs.contains(a.call.name), "function binders are not fresh")
        require(a.call.distance.isInstanceOf[Distance.Variable], "function distance must be abstract")
        val callKinds = ks.updated(a.call.distance.asInstanceOf[Distance.Variable].name, Annotations.Kind.Distance)
        val contexts = FunctionTypes.contexts(function, a.region, a.call.distance)
        var bodyKinds = callKinds
        contexts.foreach { (n, k) =>
          Annotations.validateKind(k, bodyKinds, rs + a.region)
          require(!bodyKinds.contains(n), "call-context binder is not fresh")
          bodyKinds = bodyKinds.updated(n, k)
        }
        term(body, bs.updated(a.call.name, a.call), rs + a.region, a.region, vs.updated(parameter, argument), cs, bodyKinds)
      case ValueNode.CapabilityLambda(r, operations, requirements, body) =>
        v.tpe match {
          case Type.CapabilityPoly(parameter, declared, result, _, _, obligations) =>
            require(r == parameter && TypeEquality.sameOperations(operations, declared), "capability abstraction contract mismatch")
            require(requirements == obligations.map(_.binding), "residual telescope differs from its bounded type")
            require(TypeEquality.same(body.tpe, result), "capability abstraction result mismatch")
          case _ => throw Diagnostic("Core: capability abstraction has a non-polymorphic type")
        }
        requirements.foreach(b => require(b.constraint.free.subsetOf(rs + r), "unbound residual constraint region"))
        require(!rs(r), "capability region is not fresh")
        require(requirements.map(_.name).distinct.size == requirements.size, "duplicate residual evidence binder")
        require(requirements.forall(b => !bs.contains(b.name) && b.distance.isInstanceOf[Distance.Variable]), "invalid residual evidence binder")
        value(body, bs ++ requirements.map(b => b.name -> b), rs + r, region, vs, cs.updated(r, operations), ks ++ requirements.map(b => b.distance.asInstanceOf[Distance.Variable].name -> Annotations.Kind.Distance))
      case ValueNode.Instantiate(f, c, evs) =>
        value(f, bs, rs, region, vs, cs, ks); value(c, bs, rs, region, vs, cs, ks); evs.foreach(proof(_, bs, rs))
        f.tpe match {
          case Type.CapabilityPoly(parameter, _, _, _, _, obligations) =>
            require(evs.size == obligations.size, "missing residual evidence argument")
            val actual = c.tpe match {
              case Type.Capability(r) => r
              case _ => throw Diagnostic("Core: non-capability instantiation argument")
            }
            obligations.zip(evs).foreach { (o, ev) =>
              require(ev.constraint == o.constraint.substitute(parameter, actual), "incorrect residual evidence argument")
            }
          case _ => throw Diagnostic("Core: non-polymorphic capability application")
        }
      case ValueNode.Constructor(n, args) =>
        val (owner, ctor) = constructors.getOrElse(n, throw Diagnostic("Core: unknown constructor " + n))
        require(Contracts.base(v.tpe) == Type.Data(owner), "constructor result type mismatch")
        require(args.size == ctor.fields.size, "constructor arity mismatch")
        args.zip(ctor.fields).foreach { (arg, field) =>
          require(TypeEquality.same(Contracts.erase(arg.tpe), Contracts.erase(field._2)), "constructor field type mismatch")
          value(arg, bs, rs, region, vs, cs, ks)
        }
      case ValueNode.Primitive(_, args) => args.foreach(value(_, bs, rs, region, vs, cs, ks))
      case ValueNode.KindLambda(parameter, kind, body) =>
        require(v.tpe == Type.KindPoly(parameter, kind, body.tpe), "kind abstraction contract mismatch")
        require(!ks.contains(parameter) && !rs(parameter) && !bs.contains(parameter), "kind binder is not fresh")
        Annotations.validateKind(kind, ks, rs)
        val regions = if kind == Annotations.Kind.Region then rs + parameter else rs
        val bindings = kind match {
          case Annotations.Kind.Evidence(a, d, b) => bs.updated(parameter, Binding(parameter, Constraint(a, b), d))
          case _ => bs
        }
        value(body, bindings, regions, region, vs, cs, ks.updated(parameter, kind))
      case ValueNode.InstantiateKind(body, argument) =>
        value(body, bs, rs, region, vs, cs, ks)
        Annotations.validateArgument(argument, ks, rs)
        argument match {
          case Annotations.Argument.Evidence(Annotations.Proof.Checked(w)) => proof(w, bs, rs)
          case _ => ()
        }
        body.tpe match {
          case Type.KindPoly(parameter, kind, result) =>
            require(Annotations.sameKind(kind, Annotations.kindOf(argument, ks)), "annotation argument kind mismatch")
            require(TypeEquality.same(v.tpe, Annotations.substitution(parameter, argument).typ(result)), "annotation application result mismatch")
          case _ => throw Diagnostic("Core: annotation application of a non-polymorphic value")
        }
      case ValueNode.TypeLambda(parameter, body) =>
        require(v.tpe == Type.TypePoly(parameter, body.tpe), "type abstraction contract mismatch")
        value(body, bs, rs, region, vs, cs, ks)
      case ValueNode.InstantiateType(body, argument) =>
        body.tpe match {
          case Type.TypePoly(parameter, result) =>
            require(TypeEquality.same(v.tpe, Contracts.instantiate(result, parameter, argument)), "type application result mismatch")
          case _ => throw Diagnostic("Core: type application of a non-polymorphic value")
        }
        value(body, bs, rs, region, vs, cs, ks)
      case ValueNode.ProofAbstraction(body) => value(body, bs, rs, region, vs, cs, ks)
      case ValueNode.ProofApplication(body) => value(body, bs, rs, region, vs, cs, ks)
      case ValueNode.Variable(n) =>
        val declared = vs.getOrElse(n, throw Diagnostic("Core: unbound value " + n))
        require(TypeEquality.same(Contracts.erase(declared), Contracts.erase(v.tpe)), "variable type differs from its binding")
      case ValueNode.Capability(r) =>
        require(cs.contains(r) && v.tpe == Type.Capability(r), "unbound or mistyped capability")
      case ValueNode.Integer(_) => require(Contracts.base(v.tpe) == Type.Int, "mistyped integer")
      case ValueNode.Boolean(_) => require(Contracts.base(v.tpe) == Type.Bool, "mistyped boolean")
      case ValueNode.Unit => require(Contracts.base(v.tpe) == Type.Unit, "mistyped unit")
      case ValueNode.Token(n) => require(v.tpe == Type.Token(n), "mistyped token")
    }
    def term(t: Term, bs: Map[String, Binding], rs: Set[String], region: String, vs: Map[String, Type], cs: Map[String, List[Signature]], ks: Map[String, Annotations.Kind]): Unit = {
      require(t.region == region, "term moved to a different region")
      t.node match {
        case Node.Return(v) => value(v, bs, rs, region, vs, cs, ks)
        case Node.Assert => ()
        case Node.Let(n, first, body) =>
          term(first, bs, rs, region, vs, cs, ks)
          term(body, bs, rs, region, vs.updated(n, first.tpe.value), cs, ks)
        case Node.LetRec(n, f, body) =>
          value(f, bs, rs, region, vs.updated(n, f.tpe), cs, ks)
          term(body, bs, rs, region, vs.updated(n, f.tpe), cs, ks)
        case Node.Ascribe(body) => term(body, bs, rs, region, vs, cs, ks)
        case Node.If(c, a, b) => value(c, bs, rs, region, vs, cs, ks); term(a, bs, rs, region, vs, cs, ks); term(b, bs, rs, region, vs, cs, ks)
        case Node.Match(v, cases) =>
          value(v, bs, rs, region, vs, cs, ks)
          val owner = Contracts.base(v.tpe) match {
            case Type.Data(n) => n
            case _ => throw Diagnostic("Core: match scrutinee is not data")
          }
          val schema = datatypes.find(_.name == owner).getOrElse(throw Diagnostic("Core: unknown matched datatype"))
          require(cases.map(_.constructor).distinct.size == cases.size &&
            cases.map(_.constructor).toSet == schema.constructors.map(_.name).toSet, "non-exhaustive or duplicate match")
          cases.foreach { c =>
            val fields = schema.constructors.find(_.name == c.constructor).get.fields
            require(c.bindings.size == fields.size && c.bindings.map(_._1).distinct.size == c.bindings.size, "invalid pattern binders")
            require(c.bindings.zip(fields).forall((a, b) => TypeEquality.same(Contracts.erase(a._2), Contracts.erase(b._2))), "mistyped pattern field")
            require(TypeEquality.same(Contracts.erase(c.body.tpe.value), Contracts.erase(t.tpe.value)), "match branch result mismatch")
            term(c.body, bs, rs, region, vs ++ c.bindings, cs, ks)
          }
        case Node.Apply(f, a, evs, contexts) =>
          value(f, bs, rs, region, vs, cs, ks); value(a, bs, rs, region, vs, cs, ks); evs.foreach(proof(_, bs, rs))
          f.tpe match {
            case f: Type.Function =>
              require(evs.size == 1 && f.region.contains(evs.head.constraint.to) && evs.head.constraint.from == region, "incorrect function call evidence")
              val parameters = FunctionTypes.contexts(f, region, evs.head.distance)
              require(parameters.size == contexts.size, "incorrect number of call-context arguments")
              var result = FunctionTypes.result(f, region, evs.head.distance)
              parameters.zip(contexts).foreach { case ((name, kind), context) =>
                Annotations.validateArgument(Annotations.Argument.Context(context), ks, rs)
                require(Annotations.sameKind(kind, Annotations.Kind.Context(Annotations.depth(context, ks))), "incorrect call-context depth")
                result = Annotations.substitution(name, Annotations.Argument.Context(context)).computation(result)
              }
              require(TypeEquality.same(Contracts.erase(t.tpe.value), Contracts.erase(result.value)),
                "function application has an incorrect caller-indexed result")
            case Type.Continuation(_, _, target, _, _) => require(evs.isEmpty && contexts.isEmpty && region == target, "continuation used outside its region")
            case Type.FixedFunction(_, _, _, Some(target), _) => require(evs.isEmpty && contexts.isEmpty && region == target, "local function used outside its region")
            case _ => throw Diagnostic("Core: invalid function application")
          }
        case Node.Perform(receiver, operation, a, ev, typing) =>
          value(a, bs, rs, region, vs, cs, ks); proof(ev, bs, rs)
          require(ev.constraint.from == region, "operation evidence starts in the wrong region")
          val target = vs.get(receiver) match {
            case Some(Type.Capability(r)) => r
            case Some(_) => throw Diagnostic("Core: non-capability operation receiver")
            case None => receiver
          }
          val operations = cs.getOrElse(target, throw Diagnostic("Core: unbound operation receiver"))
          require(ev.constraint.to == target, "operation evidence targets a different capability")
          require(operations.exists(_.name == operation) && typing.signature.name == operation, "unknown operation slot")
          require(TypeEquality.same(Contracts.erase(typing.signature.result), Contracts.erase(typing.inferred.value)), "operation result differs from its signature")
          Annotations.validateArgument(Annotations.Argument.Context(typing.context), ks, rs)
          require(Evidence.sameDistance(ev.distance, Annotations.depth(typing.context, ks)), "ATC depth differs from evidence distance")
          typing.inferred.effect match {
            case Syntax.Effect.Change(initial, finalAnswer) =>
              require(TypeEquality.same(initial, typing.context.plug(typing.signature.initialAnswer)), "incorrect ATC plugging")
              def peel(c: AnswerContext, answer: Computation): Unit = c match {
                case AnswerContext.Hole => () // The hole's refinement subtyping obligation was discharged by Checker.
                case c @ (_: AnswerContext.Variable | _: AnswerContext.Compose) =>
                  require(TypeEquality.same(answer, c.plug(typing.signature.finalAnswer)), "abstract ATC differs from operation answer")
                case AnswerContext.Layer(v, i, rest, returned) => answer.effect match {
                  case Syntax.Effect.Change(ai, af) =>
                    require(TypeEquality.same(Computation(v, Syntax.Effect.Change(i, af), returned), answer), "ATC differs from inherited answer")
                    peel(rest, af)
                  case _ => throw Diagnostic("Core: ATC exceeds inherited answer layers")
                }
              }
              peel(typing.context, finalAnswer)
            case _ => throw Diagnostic("Core: operation has no answer transition")
          }
        case Node.Handle(r, edge, body, returned, ret, ops) =>
          require(edge.constraint == Constraint(r, region) && edge.distance == Distance.Numeral(1), "incorrect handler edge")
          require(!rs(r) && !bs.contains(edge.name), "handler binders are not fresh")
          term(body, bs.updated(edge.name, edge), rs + r, r, vs, cs.updated(r, ops.map(_.signature)), ks)
          term(ret, bs, rs, region, vs.updated(returned, body.tpe.value), cs, ks)
          ops.foreach { o =>
            val k = Type.Continuation(o.signature.result, o.signature.initialAnswer, region, Set.empty, o.signature.returned)
            term(o.body, bs, rs, region, vs.updated(o.parameter, o.signature.argument).updated(o.continuation, k), cs, ks)
          }
      }
    }
    term(root, Map.empty, Set.empty, Top, Map.empty, Map.empty, Map.empty)
  }

  def show(program: Program): String = {
    val out = new StringBuilder
    def line(n: Int, s: String): Unit = { out.append("  " * n).append(s).append('\n'); () }
    def value(v: Value, n: Int): Unit = {
      val label = v.node match {
        case ValueNode.Integer(x) => x.toString
        case ValueNode.Boolean(x) => x.toString
        case ValueNode.Variable(x) => x
        case ValueNode.Capability(r) => s"cap($r)"
        case ValueNode.Token(x) => s"token($x)"
        case ValueNode.Constructor(n, _) => n
        case ValueNode.Primitive(op, _) => op
        case ValueNode.FixedLambda(p, r, _) => s"lambda $p at [$r]"
        case ValueNode.KindLambda(p, k, _) => s"KindLambda [$p: $k]"
        case ValueNode.InstantiateKind(_, a) => s"InstantiateKind [$a]"
        case ValueNode.TypeLambda(p, _) => s"TypeLambda [$p]"
        case ValueNode.InstantiateType(_, t) => s"InstantiateType [${Syntax.show(t)}]"
        case _ => v.node.productPrefix
      }
      line(n, s"$label: ${Syntax.show(v.tpe)}")
      v.node match {
        case ValueNode.FixedLambda(_, _, body) => term(body, n + 1)
        case ValueNode.Lambda(p, a, body) => line(n + 1, s"Λ ${a.region}, ${a.call}; parameter $p"); term(body, n + 1)
        case ValueNode.CapabilityLambda(r, _, bs, body) => line(n + 1, s"Λ $r, residual parameters ${bs.mkString(", ")}"); value(body, n + 1)
        case ValueNode.Instantiate(f, c, es) => line(n + 1, s"evidence arguments $es"); value(f, n + 1); value(c, n + 1)
        case ValueNode.Primitive(_, args) => args.foreach(value(_, n + 1))
        case ValueNode.Constructor(_, args) => args.foreach(value(_, n + 1))
        case ValueNode.KindLambda(_, _, b) => value(b, n + 1)
        case ValueNode.InstantiateKind(b, _) => value(b, n + 1)
        case ValueNode.TypeLambda(_, b) => value(b, n + 1)
        case ValueNode.InstantiateType(b, _) => value(b, n + 1)
        case ValueNode.ProofAbstraction(b) => value(b, n + 1)
        case ValueNode.ProofApplication(b) => value(b, n + 1)
        case _ => ()
      }
    }
    def term(t: Term, n: Int): Unit = {
      line(n, s"${t.node.productPrefix} @ ${t.region}: ${Syntax.show(t.tpe)}")
      t.node match {
        case Node.Return(v) => value(v, n + 1)
        case Node.Let(name, a, b) => line(n + 1, s"bind $name"); term(a, n + 1); term(b, n + 1)
        case Node.LetRec(name, f, b) => line(n + 1, s"bind rec $name"); value(f, n + 1); term(b, n + 1)
        case Node.Apply(f, a, es, contexts) => line(n + 1, s"evidence arguments $es"); value(f, n + 1); value(a, n + 1)
        case Node.If(c, a, b) => value(c, n + 1); term(a, n + 1); term(b, n + 1)
        case Node.Match(v, cases) =>
          value(v, n + 1)
          cases.foreach { c => line(n + 1, "case " + c.constructor + c.bindings.map(_._1).mkString("(", ",", ")")); term(c.body, n + 2) }
        case Node.Ascribe(b) => term(b, n + 1)
        case Node.Perform(c, op, a, ev, typing) =>
          line(n + 1, s"$c.$op evidence=$ev; distance=${ev.distance}; ATC=${typing.context}"); value(a, n + 1)
        case Node.Handle(_, edge, b, returned, ret, ops) =>
          line(n + 1, s"bind $edge"); term(b, n + 1)
          line(n + 1, s"return $returned"); term(ret, n + 2)
          ops.foreach { o =>
            line(n + 1, s"${o.signature.name}(${o.parameter}) resume ${o.continuation}")
            term(o.body, n + 2)
          }
        case Node.Assert => ()
      }
    }
    program.datatypes.foreach(d => line(0, "data " + d.name + " { " + d.constructors.map(c => c.name + c.fields.map((n, t) => n + ": " + Syntax.show(t)).mkString("(", ", ", ")")).mkString("; ") + " }"))
    program.measures.foreach { m =>
      line(0, "measure " + m.name + m.parameters.mkString("(", ", ", ")") + ": " + m.result)
      m.cases.foreach(c => line(1, c.constructor + c.parameters.mkString("(", ", ", ")") + " => " + Logic.show(c.body)))
    }
    term(program.body, 0); out.result()
  }
}

package effekt.atm

/** Experimental ATM source language, before elaboration to ATM Core/Lifted.
 * Handler names are capabilities. Regions, evidence and answer contexts can
 * either be reconstructed or supplied through checked explicit annotations.
 */
object Syntax {
  // Binders of the implicit A_Abs call telescope. They scope over the
  // contextual parameters and result, never over the value-argument domain.
  case class Caller(region: String, distance: String,
                    contexts: List[(String, Annotations.Kind)] = Nil)
  enum Type {
    case Int, Bool, Unit
    case Token(name: String)
    // Source-only reference. Capability binders expand it to checked structural
    // operation contracts; it is not a nominal runtime value type.
    case Interface(name: String, arguments: List[Type])
    case Data(name: String)
    case Variable(name: String)
    case Pair(first: Type, second: Type)
    case Refined(parameter: String, base: Type, predicate: Logic.Expr)
    case TypePoly(parameter: String, body: Type)
    case PredicatePoly(parameter: String, arguments: List[Logic.Sort], body: Type)
    case Capability(name: String)
    // Captures are legacy emitter metadata. Only the region participates in
    // paper typing, subtyping, equality and free-region checks.
    case Function(argument: Type, result: Computation, captures: Set[String] = Set.empty,
                  region: Option[String] = None, parameter: Option[String] = None, caller: Option[Caller] = None)
    // A declarative arrow: callable at exactly its region, without a fresh
    // region/distance/evidence abstraction. The source contract is (T) =>[r] C.
    case FixedFunction(argument: Type, result: Computation, captures: Set[String] = Set.empty,
                       region: Option[String] = None, parameter: Option[String] = None)
    // Internal continuation arrow; it may be viewed as an exact-region local
    // arrow, but never as an ordinary region-polymorphic Function.
    case Continuation(argument: Type, result: Computation, region: String,
                      captures: Set[String], parameter: Option[String] = None)
    case CapabilityPoly(parameter: String, operations: List[Signature], body: Type,
                        captures: Set[String] = Set.empty, context: Option[String] = None,
                        obligations: List[Evidence.Obligation] = Nil)
    case EffectPoly(parameter: String, body: Type, bound: EffectBound = EffectBound.Any)
    case KindPoly(parameter: String, kind: Annotations.Kind, body: Type)
    // The value projection of an abstract computation; never a source value type.
    case AbstractResult(computation: ComputationForm)
  }
  enum EffectBound {
    case Any, Repeatable
    case After(prefix: Effect)
  }
  enum Effect {
    case Pure
    case Change(initial: Computation, result: Computation)
    case Variable(name: String)
    case Sequence(first: Effect, second: Effect)
    case Abstract
  }
  // The returned-value binder scopes over the initial answer only.
  enum ComputationForm {
    case Variable(name: String)
    case Context(context: Evidence.AnswerContext, argument: Computation)
  }
  case class Computation(value: Type, effect: Effect = Effect.Pure, returned: Option[String] = None,
                         form: Option[ComputationForm] = None)

  // A bare bracket argument can have several syntactic readings (e.g. a name
  // or 0). The quantified parameter selects its grammar during checking.
  enum PolymorphicArgument {
    case ValueType(value: Type)
    case Kind(value: Annotations.Argument)
    case AnswerEffect(value: Effect)
    case Predicate(parameters: List[(String, Logic.Sort)], body: Logic.Expr)
  }

  enum Value {
    case Integer(value: BigInt)
    case Boolean(value: scala.Boolean)
    case Unit
    case Token(name: String)
    case Constructor(name: String, arguments: List[Value])
    case Capability(name: String)
    case Variable(name: String)
    case Lambda(parameter: String, argument: Type, result: Computation, body: Term, caller: Option[String] = None)
    case ContextLambda(parameter: String, argument: Type, result: Computation,
                       contexts: List[(String, Annotations.Kind)], body: Term, caller: Option[String] = None)
    case FixedLambda(parameter: String, argument: Option[Type], result: Option[Computation], body: Term, region: Option[String] = None)
    case InferredLambda(parameter: String, argument: Type, body: Term, caller: Option[String] = None)
    case UnannotatedLambda(parameter: String, result: Option[Computation], body: Term, caller: Option[String] = None)
    case CapabilityLambda(parameter: String, operations: List[Signature], body: Value, scoped: scala.Boolean = false)
    case Instantiate(function: Value, capability: Value)
    case EffectLambda(parameter: String, body: Value, bound: EffectBound = EffectBound.Any)
    case InstantiateEffect(function: Value, effect: Effect)
    case TypeLambda(parameter: String, body: Value)
    case InstantiateType(function: Value, argument: Type)
    case PredicateLambda(parameter: String, arguments: List[Logic.Sort], body: Value)
    case InstantiatePredicate(function: Value, parameters: List[(String, Logic.Sort)], predicate: Logic.Expr)
    case KindLambda(parameter: String, kind: Annotations.Kind, body: Value)
    case InstantiateKind(function: Value, argument: Annotations.Argument)
    case InstantiatePolymorphic(function: Value, alternatives: List[PolymorphicArgument])
    case Primitive(name: String, arguments: List[Value])
  }
  enum Term {
    case TypeAlias(name: String, definition: Type, body: Term)
    case AnnotationAlias(name: String, definition: Annotations.Argument, body: Term)
    case Return(value: Value)
    case Let(name: String, binding: Term, body: Term)
    case Apply(function: Value, argument: Value)
    case Ascribe(body: Term, expected: Computation)
    case Assert(condition: Value)
    case LetRec(name: String, parameter: String, argument: Type, result: Computation, definition: Term, body: Term, region: Option[String] = None, caller: Option[String] = None)
    // Compatibility spelling selected by return effect; checked like ordinary returns.
    case ExplicitReturn(body: Term)
    case If(condition: Value, whenTrue: Term, whenFalse: Term)
    case Match(scrutinee: Value, cases: List[MatchCase])
    case Perform(capability: String, operation: String, argument: Value)
    case AnnotatedPerform(capability: String, operation: String, argument: Value,
                          evidence: Option[Annotations.Proof], context: Option[Evidence.AnswerContext])
    // A lone annotation name is classified by its scoped kind during checking.
    case SingleAnnotatedPerform(capability: String, operation: String, argument: Value, annotation: String)
    case Handle(capability: String, result: Computation, operations: List[Operation],
                body: Term, returned: String, onReturn: Term)
    case InferredHandle(capability: String, operations: List[Operation],
                        body: Term, returned: String, onReturn: Term)
  }
  case class MatchCase(constructor: String, parameters: List[String], body: Term)
  case class Operation(name: String, argument: Type, result: Type,
                       answers: Option[(Computation, Computation)],
                       parameter: String, continuation: String, body: Term,
                       argumentIdentity: Option[String] = None, returned: Option[String] = None,
                       interfaceName: Option[String] = None,
                       argumentAnnotated: Boolean = true, resultAnnotated: Boolean = true,
                       argumentAnnotations: List[Option[Type]] = Nil, interfaceArguments: List[Type] = Nil) {
    // Missing source annotations are filled from the enclosing handler before
    // checking. Core and both backends only receive explicit contracts.
    def signature: Signature = {
      val (initial, finalAnswer) = answers.getOrElse(throw Diagnostic(s"Unresolved answer contract for operation $name"))
      Signature(name, argument, result, initial, finalAnswer, argumentIdentity, returned)
    }
    def initialAnswer: Computation = signature.initialAnswer
    def finalAnswer: Computation = signature.finalAnswer
  }
  case class Signature(name: String, argument: Type, result: Type,
                       initialAnswer: Computation, finalAnswer: Computation,
                       parameter: Option[String] = None, returned: Option[String] = None)
  case class DataConstructor(name: String, fields: List[(String, Type)])
  case class DataDeclaration(name: String, constructors: List[DataConstructor])
  // Native interface headers specify value signatures. Each implementation
  // supplies or infers its ATM answer contract independently.
  case class InterfaceOperation(name: String, parameter: Option[String], argument: Type,
                                result: Type, returned: Option[String] = None, arity: Int = 0)
  case class InterfaceDeclaration(name: String, operations: List[InterfaceOperation], typeParameters: List[String] = Nil)
  case class Program(result: Computation, body: Term, datatypes: List[DataDeclaration] = Nil,
                     measures: List[Logic.Measure] = Nil, interfaces: List[InterfaceDeclaration] = Nil,
                     inferResult: Boolean = false, typeAliases: List[(String, Type)] = Nil)

  enum DiagnosticKind {
    case ParseError, ContractFailure, Unsupported, VerificationUnavailable, TypeError
  }
  case class Diagnostic(message: String, kind: DiagnosticKind = DiagnosticKind.TypeError) extends Exception(message)

  def show(tpe: Type): String = tpe match {
    case Type.Int => "Int"
    case Type.Bool => "Bool"
    case Type.Unit => "Unit"
    case Type.Token(name) => name
    case Type.Interface(name, arguments) => name + (if arguments.isEmpty then "" else arguments.map(show).mkString("[", ", ", "]"))
    case Type.Data(name) => name
    case Type.Variable(name) => displayName(name)
    case Type.Pair(a, b) => s"(${show(a)}, ${show(b)})"
    case Type.Refined(name, base, predicate) => s"{${displayName(name)}: ${show(base)} | ${Logic.show(predicate)}}"
    case Type.TypePoly(name, body) => s"forall type ${displayName(name)}. ${show(body)}"
    case Type.PredicatePoly(name, args, body) => s"forall pred ${displayName(name)}(${args.mkString(", ")}). ${show(body)}"
    case Type.Capability(name) => s"cap(${displayName(name)})"
    case Type.Function(arg, result, captures, region, parameter, caller) =>
      val telescope = caller.fold("")(c => s"forall caller ${displayName(c.region)}, ${displayName(c.distance)}" +
        c.contexts.map((n, k) => s", ${displayName(n)}: $k").mkString + ". ")
      telescope + s"(${parameter.fold("")(p => displayName(p) + ": ")}${show(arg)} -> ${show(result)}) at ${captures.toList.sorted.map(displayName).mkString("{", ",", "}")}" +
        region.fold("")(r => s" @${displayName(r)}")
    case Type.FixedFunction(arg, result, _, region, parameter) =>
      s"(${parameter.fold("")(p => displayName(p) + ": ")}${show(arg)}) =>[${region.map(displayName).getOrElse("<unresolved>")}] ${show(result)}"
    case Type.Continuation(arg, result, region, _, parameter) =>
      s"(${parameter.fold("")(p => displayName(p) + ": ")}${show(arg)} ~> ${show(result)}) @${displayName(region)}"
    case Type.CapabilityPoly(parameter, operations, body, _, _, obligations) =>
      val contract = operations.map(o => s"def ${o.name}(${show(o.argument)}): ${show(o.result)} / (${show(o.initialAnswer)} => ${show(o.finalAnswer)});").mkString(" ")
      val requirements = if obligations.isEmpty then "" else s" where ${obligations.map(_.constraint).mkString(", ")}"
      s"(${displayName(parameter)}: { $contract }) => ${show(body)}$requirements"
    case Type.EffectPoly(parameter, body, bound) =>
      val constraint = bound match {
        case EffectBound.Any => ""
        case EffectBound.Repeatable => " repeatable"
        case EffectBound.After(prefix) => s" after (${show(prefix)})"
      }
      s"forall ${displayName(parameter)}: Eff$constraint. ${show(body)}"
    case Type.KindPoly(parameter, kind, body) => s"forall ${displayName(parameter)}: $kind. (${show(body)})"
    case Type.AbstractResult(c) => s"value(${Annotations.show(c)})"
  }
  private def displayName(name: String): String = name.takeWhile(_ != '#')
  def show(tpe: Computation): String = tpe.form.fold(
    s"${tpe.returned.fold("")(n => displayName(n) + ": ")}${show(tpe.value)} / ${show(tpe.effect)}")(Annotations.show)
  def show(effect: Effect): String = effect match {
    case Effect.Pure => "pure"
    case Effect.Change(initial, result) => s"(${show(initial)} => ${show(result)})"
    case Effect.Variable(name) => displayName(name)
    case Effect.Sequence(first, second) => s"(${show(first)} ; ${show(second)})"
    case Effect.Abstract => "<abstract computation>"
  }
}

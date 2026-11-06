package effekt.atm

/** Experimental ATM source language, before elaboration to ATM Core/Lifted.
 * Handler names are capabilities; users write neither regions nor evidence.
 * Regions and evidence are reconstructed by the algorithmic checker.
 */
object Syntax {
  enum Type {
    case Int, Bool, Unit
    case Token(name: String)
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
                  region: Option[String] = None, parameter: Option[String] = None)
    // A declarative arrow: callable at exactly its region, without a fresh
    // region/distance/evidence abstraction. The source uses local or local[h].
    case LocalFunction(argument: Type, result: Computation, captures: Set[String] = Set.empty,
                       region: Option[String] = None, parameter: Option[String] = None)
    // Internal continuation arrow; it may be viewed as an exact-region local
    // arrow, but never as an ordinary region-polymorphic Function.
    case Continuation(argument: Type, result: Computation, region: String,
                      captures: Set[String], parameter: Option[String] = None)
    case CapabilityPoly(parameter: String, operations: List[Signature], body: Type,
                        captures: Set[String] = Set.empty, context: Option[String] = None,
                        obligations: List[Evidence.Obligation] = Nil)
    case EffectPoly(parameter: String, body: Type, bound: EffectBound = EffectBound.Any)
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
  }
  // The returned-value binder scopes over the initial answer only.
  case class Computation(value: Type, effect: Effect = Effect.Pure, returned: Option[String] = None)

  enum Value {
    case Integer(value: BigInt)
    case Boolean(value: scala.Boolean)
    case Unit
    case Token(name: String)
    case Constructor(name: String, arguments: List[Value])
    case Capability(name: String)
    case Variable(name: String)
    case Lambda(parameter: String, argument: Type, result: Computation, body: Term)
    case LocalLambda(parameter: String, argument: Option[Type], result: Option[Computation], body: Term, region: Option[String] = None)
    case InferredLambda(parameter: String, argument: Type, body: Term)
    case UnannotatedLambda(parameter: String, result: Option[Computation], body: Term)
    case CapabilityLambda(parameter: String, operations: List[Signature], body: Value, scoped: scala.Boolean = false)
    case Instantiate(function: Value, capability: Value)
    case EffectLambda(parameter: String, body: Value, bound: EffectBound = EffectBound.Any)
    case InstantiateEffect(function: Value, effect: Effect)
    case TypeLambda(parameter: String, body: Value)
    case InstantiateType(function: Value, argument: Type)
    case PredicateLambda(parameter: String, arguments: List[Logic.Sort], body: Value)
    case InstantiatePredicate(function: Value, parameters: List[(String, Logic.Sort)], predicate: Logic.Expr)
    case Primitive(name: String, arguments: List[Value])
  }
  enum Term {
    case TypeAlias(name: String, definition: Type, body: Term)
    case Return(value: Value)
    case Let(name: String, binding: Term, body: Term)
    case Apply(function: Value, argument: Value)
    case Ascribe(body: Term, expected: Computation)
    case Assert(condition: Value)
    case LetRec(name: String, parameter: String, argument: Type, result: Computation, definition: Term, body: Term, local: Boolean = false)
    // Only permitted as a handler return clause, selected by return effect.
    case ExplicitReturn(body: Term)
    case If(condition: Value, whenTrue: Term, whenFalse: Term)
    case Match(scrutinee: Value, cases: List[MatchCase])
    case Perform(capability: String, operation: String, argument: Value)
    case Handle(capability: String, result: Computation, operations: List[Operation],
                body: Term, returned: String, onReturn: Term)
    case InferredHandle(capability: String, operations: List[Operation],
                        body: Term, returned: String, onReturn: Term)
  }
  case class MatchCase(constructor: String, parameters: List[String], body: Term)
  case class Operation(name: String, argument: Type, result: Type,
                       initialAnswer: Computation, finalAnswer: Computation,
                       parameter: String, continuation: String, body: Term,
                       argumentIdentity: Option[String] = None, returned: Option[String] = None) {
    def signature: Signature = Signature(name, argument, result, initialAnswer, finalAnswer, argumentIdentity, returned)
  }
  case class Signature(name: String, argument: Type, result: Type,
                       initialAnswer: Computation, finalAnswer: Computation,
                       parameter: Option[String] = None, returned: Option[String] = None)
  case class DataConstructor(name: String, fields: List[(String, Type)])
  case class DataDeclaration(name: String, constructors: List[DataConstructor])
  case class Program(result: Computation, body: Term, datatypes: List[DataDeclaration] = Nil,
                     measures: List[Logic.Measure] = Nil)

  enum DiagnosticKind {
    case ParseError, ContractFailure, Unsupported, VerificationUnavailable, TypeError
  }
  case class Diagnostic(message: String, kind: DiagnosticKind = DiagnosticKind.TypeError) extends Exception(message)

  def show(tpe: Type): String = tpe match {
    case Type.Int => "Int"
    case Type.Bool => "Bool"
    case Type.Unit => "Unit"
    case Type.Token(name) => name
    case Type.Data(name) => name
    case Type.Variable(name) => displayName(name)
    case Type.Pair(a, b) => s"(${show(a)}, ${show(b)})"
    case Type.Refined(name, base, predicate) => s"{${displayName(name)}: ${show(base)} | ${Logic.show(predicate)}}"
    case Type.TypePoly(name, body) => s"forall type ${displayName(name)}. ${show(body)}"
    case Type.PredicatePoly(name, args, body) => s"forall pred ${displayName(name)}(${args.mkString(", ")}). ${show(body)}"
    case Type.Capability(name) => s"cap(${displayName(name)})"
    case Type.Function(arg, result, captures, region, parameter) =>
      s"(${parameter.fold("")(p => displayName(p) + ": ")}${show(arg)} -> ${show(result)}) at ${captures.toList.sorted.map(displayName).mkString("{", ",", "}")}" +
        region.fold("")(r => s" @${displayName(r)}")
    case Type.LocalFunction(arg, result, _, region, parameter) =>
      s"local[${region.map(displayName).getOrElse("here")}] (${parameter.fold("")(p => displayName(p) + ": ")}${show(arg)} => ${show(result)})"
    case Type.Continuation(arg, result, region, _, parameter) =>
      s"(${parameter.fold("")(p => displayName(p) + ": ")}${show(arg)} ~> ${show(result)}) @${displayName(region)}"
    case Type.CapabilityPoly(parameter, _, body, _, _, obligations) =>
      s"forall cap ${displayName(parameter)}[${obligations.map(_.constraint).mkString(", ")}]. ${show(body)}"
    case Type.EffectPoly(parameter, body, bound) =>
      val constraint = bound match {
        case EffectBound.Any => ""
        case EffectBound.Repeatable => " repeatable"
        case EffectBound.After(prefix) => s" after (${show(prefix)})"
      }
      s"forall effect ${displayName(parameter)}$constraint. ${show(body)}"
  }
  private def displayName(name: String): String = name.takeWhile(_ != '#')
  def show(tpe: Computation): String = s"${tpe.returned.fold("")(n => displayName(n) + ": ")}${show(tpe.value)} / ${show(tpe.effect)}"
  def show(effect: Effect): String = effect match {
    case Effect.Pure => "pure"
    case Effect.Change(initial, result) => s"(${show(initial)} => ${show(result)})"
    case Effect.Variable(name) => displayName(name)
    case Effect.Sequence(first, second) => s"(${show(first)} ; ${show(second)})"
  }
}

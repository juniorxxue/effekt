package effekt.atm

import Syntax.*
import Checker.*

/** Type-directed answer packaging into ordinary Effekt. Each delimiter has a
 * finite sum of its answer representations. No cast or Core typing relaxation
 * is used. Decoders retain a panic branch to expose a broken static invariant.
 * The source checker, not these checks, rejects invalid answer transitions.
 */
object Lowering {
  def apply(program: CheckedProgram): String = { Elaboration.validate(program.body); new Generator().program(program) }

  private class Generator {
    private var counter = 0
    private val declarations = scala.collection.mutable.ListBuffer.empty[String]
    private val tokens = scala.collection.mutable.Set.empty[String]
    private val interfaces = scala.collection.mutable.Map.empty[List[String], String]
    private def fresh(prefix: String): String = { counter += 1; s"atm${prefix}${counter}" }
    // v0.3.0 permits a block as a val RHS or branch, but not as a bare
    // expression at the end of another block. Splice generated statements there.
    private def statements(code: String): String =
      if code.startsWith("{ ") then code.substring(2, code.length - 2) else code
    private case class Env(values: Map[String, String] = Map.empty, caps: Map[String, String] = Map.empty,
                           capTypes: Map[String, String] = Map.empty, types: Map[String, String] = Map.empty,
                           recursive: Map[String, String] = Map.empty) {
      def bind(name: String, target: String): Env = copy(values = values.updated(name, target), recursive = recursive - name)
      def withCapability(name: String, target: String, tpe: String): Env =
        copy(caps = caps.updated(name, target), capTypes = capTypes.updated(name, tpe))
    }

    private def equality(t: Type, a: String, b: String): String = Contracts.base(t) match {
      case Type.Int => s"atmEqual($a, $b)"
      case Type.Bool => s"($a == $b)"
      case Type.Unit => "true"
      case Type.Data(n) => s"atmDataEqual$n($a, $b)"
      case Type.Pair(x, y) => s"if (${equality(x, s"$a.atmFirst", s"$b.atmFirst")}) { ${equality(y, s"$a.atmSecond", s"$b.atmSecond")} } else { false }"
      case other => throw Diagnostic("No runtime equality for " + Syntax.show(other))
    }
    def program(p: CheckedProgram): String = {
      p.datatypes.foreach { d =>
        declarations += s"type AtmData${d.name} { " + d.constructors.map(c =>
          "AtmCtor" + c.name + c.fields.map((n, t) => s"atmField${c.name}$n: ${target(t, Env())}").mkString("(", ", ", ")")).mkString("; ") + " }"
        if Datatypes.equalitySupported(Type.Data(d.name), p.datatypes) then {
        val cases = d.constructors.map { c =>
          val xs = c.fields.map(_ => fresh("Field"))
          val inner = d.constructors.map { other =>
            val ys = other.fields.map(_ => fresh("Field"))
            val comparisons = c.fields.zip(xs.zip(ys)).map { case ((_, t), (a, b)) => equality(t, a, b) }
            val equal = if c.name != other.name then "false" else comparisons.foldRight("true")((p, rest) => s"if ($p) { $rest } else { false }")
            "case AtmCtor" + other.name + ys.mkString("(", ", ", ")") + " => " + equal
          }.mkString("\n")
          "case AtmCtor" + c.name + xs.mkString("(", ", ", ")") + " => y match { " + inner + " }"
        }.mkString("\n")
        declarations += s"def atmDataEqual${d.name}(x: AtmData${d.name}, y: AtmData${d.name}): Bool = { x match { $cases } }"
      }
      }
      val body = term(p.body, Env())
      val printed = Contracts.base(p.body.tpe.value) match {
        case Type.Interface(n, _) => throw Diagnostic("Unresolved interface capability type " + n)
        case _: Type.AbstractResult => throw Diagnostic("A closed result must have a known value type")
        case Type.Int => "println(atmShowInteger(atmResult))"
        case Type.Bool => "println(atmResult)"
        case Type.Unit => "println(\"unit\")"
        case _: Type.Pair => "println(\"<pair>\")"
        case Type.Token(name) => s"println(\"$name\")"
        case _: Type.Data => "println(\"<data>\")"
        case _: Type.Capability => "println(\"<capability>\")"
        case _: Type.Variable | _: Type.Refined => throw Diagnostic("A closed program has an unprintable result type")
        case _: Type.KindPoly | _: Type.FixedFunction | _: Type.Continuation | _: Type.Function | _: Type.CapabilityPoly | _: Type.EffectPoly | _: Type.TypePoly | _: Type.PredicatePoly => "println(\"<function>\")"
      }
      s"// Generated from a statically checked ATM source program.\n$integerPrelude\n${declarations.mkString("\n\n")}\n\n" +
        s"def main() = {\n  val atmResult = $body;\n  $printed\n}\n"
    }
    private def target(tpe: Type, env: Env): String = tpe match {
      case Type.Refined(_, base, _) => target(base, env)
      case Type.Pair(a, b) => s"AtmPair[${target(a, env)}, ${target(b, env)}]"
      case Type.Variable(name) => env.types.getOrElse(name, throw Diagnostic(s"Unbound target type $name"))
      case _: Type.KindPoly | _: Type.AbstractResult =>
        throw Diagnostic("Explicit kind polymorphism uses the direct Lifted backend", DiagnosticKind.Unsupported)
      case Type.TypePoly(name, body) =>
        val parameter = fresh("Type")
        val cs = (typeCaptures(body).toList.sorted.map(env.caps) :+ "io").distinct.mkString(", ")
        s"[$parameter]() => (${target(body, env.copy(types = env.types.updated(name, parameter)))}) at {$cs}"
      case Type.PredicatePoly(_, _, body) => target(body, env)
      case Type.Int => "AtmInteger"
      case Type.Bool => "Bool"
      case Type.Unit => "Unit"
      case Type.Data(n) => s"AtmData$n"
      case Type.Capability(name) => s"${env.capTypes(name)} at {${env.caps(name)}}"
      case Type.Token(name) =>
        val target = s"AtmToken$name"
        if tokens.add(name) then declarations += s"type $target { AtmMake$name() }"
        target
      case Type.FixedFunction(argument, result, captures, _, _) => target(Type.Function(argument, result, captures), env)
      case Type.Continuation(argument, result, _, captures, _) =>
        target(Type.Function(argument, result, captures), env)
      case Type.Function(argument, result, captures, _, _, _)  =>
        val cs = (captures.toList.sorted.map(env.caps) :+ "io").distinct.mkString(", ")
        s"(${target(argument, env)}) => (${target(result.value, env)}) at {$cs}"
      case Type.CapabilityPoly(name, operations, body, captures, _, obligations) =>
        val cap = fresh("CapParameter")
        val tpe = interface(operations, env)
        val cs = (captures.toList.sorted.map(env.caps) :+ "io").distinct.mkString(", ")
        s"() {$cap: $tpe} => (${target(body, env.withCapability(name, cap, tpe))}) at {$cs}"
      case Type.EffectPoly(_, body, _) => target(body, env)
      case Type.Interface(n, _) => throw Diagnostic(s"Unresolved interface capability type $n")
    }

    private def typeCaptures(tpe: Type): Set[String] = tpe match {
      case Type.Function(_, _, cs, _, _, _) => cs
      case Type.FixedFunction(_, _, cs, _, _) => cs
      case Type.Continuation(_, _, _, cs, _) => cs
      case Type.CapabilityPoly(_, _, _, cs, _, obligations) => cs
      case Type.EffectPoly(_, b, _) => typeCaptures(b)
      case Type.TypePoly(_, b) => typeCaptures(b)
      case Type.PredicatePoly(_, _, b) => typeCaptures(b)
      case Type.Capability(n) => Set(n)
      case Type.Pair(a, b) => typeCaptures(a) ++ typeCaptures(b)
      case _ => Set.empty
    }

    // Source contracts remain distinct in the checker. At runtime capabilities
    // with the same method names share a generic nominal interface, allowing
    // one checked abstraction to accept independently allocated handlers.
    private def interface(operations: List[Signature], env: Env): String = {
      val sorted = operations.sortBy(_.name)
      val name = interfaces.getOrElseUpdate(sorted.map(_.name), {
        val name = s"AtmOperations${fresh("Interface")}"
        val parameters = sorted.indices.flatMap(i => List(s"P$i", s"R$i"))
        val methods = sorted.zipWithIndex.map((op, i) => s"def ${op.name}(x: P$i): R$i")
        declarations += s"interface $name${parameters.mkString("[", ", ", "]")} { ${methods.mkString("\n")} }"
        name
      })
      val arguments = sorted.flatMap(op => List(target(op.argument, env), target(op.result, env)))
      s"$name${arguments.mkString("[", ", ", "]")}"
    }

    private def value(v: CheckedValue, env: Env): String = v.source match {
      case Value.Integer(n) => s"atmInteger(\"$n\")"
      case Value.Boolean(b) => b.toString
      case Value.Unit => "()"
      case Value.Token(name) => target(Type.Token(name), env); s"AtmMake$name()"
      case Value.Constructor(name, _) =>
        val args = v.children.map(_ => fresh("FieldArg"))
        val bindings = args.zip(v.children).map((n, arg) => s"val $n = ${value(arg, env)};")
        s"{ ${bindings.mkString(" ")} AtmCtor$name${args.mkString("(", ", ", ")")} }"
      case Value.Capability(name) => s"box ${env.caps(name)}"
      case Value.Variable(name) => env.values(name)
      case Value.Primitive(name, _) =>
        val args = v.children.map(_ => fresh("PrimitiveArg"))
        val bindings = args.zip(v.children).map((name, arg) => s"val $name = ${value(arg, env)};")
        val result = name match {
          case "zero" => s"atmZero(${args.head})"
          case "pair" => s"AtmPair[${target(v.children.head.tpe, env)}, ${target(v.children(1).tpe, env)}](${args.head}, ${args(1)})"
          case "fst" => s"${args.head}.atmFirst"
          case "snd" => s"${args.head}.atmSecond"
          case "add" => s"atmAdd(${args.head}, ${args(1)})"
          case "not" => s"not(${args.head})"
          case "sub" => s"atmSubtract(${args.head}, ${args(1)})"
          case "mul" => s"atmMultiply(${args.head}, ${args(1)})"
          case "div" => s"atmDivide(${args.head}, ${args(1)})"
          case "mod" => s"atmRemainder(${args.head}, ${args(1)})"
          case "lt" | "le" | "gt" | "ge" =>
            s"atm${Map("lt" -> "Less", "le" -> "LessEqual", "gt" -> "Greater", "ge" -> "GreaterEqual")(name)}(${args.head}, ${args(1)})"
          case "eq" | "ne" =>
            val equal = equality(v.children.head.tpe, args.head, args(1))
            if name == "eq" then equal else s"not($equal)"
          // Arguments have already been evaluated. Avoid the old compiler's
          // thunk-based boolean operators, whose inliner can lose these locals.
          case "and" => s"if (${args.head}) { ${args(1)} } else { false }"
          case "or" => s"if (${args.head}) { true } else { ${args(1)} }"
          case _ => throw Diagnostic(s"Unknown primitive $name during lowering")
        }
        s"{ ${bindings.mkString(" ")} $result }"
      case Value.Lambda(parameter, _, _, _, _) => lambda(v, parameter, env)
      case Value.ContextLambda(parameter, _, _, _, _, _) => lambda(v, parameter, env)
      case Value.FixedLambda(parameter, _, _, _, _) => lambda(v, parameter, env)
      case Value.InferredLambda(parameter, _, _, _) => lambda(v, parameter, env)
      case Value.UnannotatedLambda(parameter, _, _, _) => lambda(v, parameter, env)
      case Value.CapabilityLambda(name, operations, _, _) =>
        val cap = fresh("CapParameter")
        val function = fresh("Function")
        val tpe = interface(operations, env)
        s"{ val $function: ${target(v.tpe, env)} = box { () {$cap: $tpe} => " +
          s"${statements(value(v.children.head, env.withCapability(name, cap, tpe)))} }; $function }"
      case Value.Instantiate(_, _) =>
        val f = fresh("Poly")
        val cap = fresh("CapArgument")
        s"{ val $f = ${value(v.children.head, env)}; val $cap = ${value(v.children(1), env)}; $f(){$cap} }"
      case _: Value.KindLambda | _: Value.InstantiateKind =>
        throw Diagnostic("Explicit kind polymorphism uses the direct Lifted backend", DiagnosticKind.Unsupported)
      case _: Value.InstantiatePolymorphic => throw Diagnostic("Unresolved polymorphic argument reached lowering")
      case Value.TypeLambda(name, _) =>
        val parameter = fresh("Type")
        val function = fresh("Polymorphic")
        val inner = env.copy(types = env.types.updated(name, parameter))
        val body = v.children.head
        s"{ def $function[$parameter](): ${target(body.tpe, inner)} = { ${statements(value(body, inner))} }; box $function }"
      case Value.InstantiateType(_, argument) =>
        val function = fresh("TypeApplication")
        s"{ val $function = ${value(v.children.head, env)}; $function[${target(argument, env)}]() }"
      case Value.EffectLambda(_, _, _) | Value.InstantiateEffect(_, _) |
           Value.PredicateLambda(_, _, _) | Value.InstantiatePredicate(_, _, _) => value(v.children.head, env)
    }

    private def lambda(v: CheckedValue, parameter: String, env: Env): String = {
        val x = fresh("Arg")
        val function = fresh("Function")
        val argument = v.tpe match {
          case Type.Function(a, _, _, _, _, _) => a
          case Type.FixedFunction(a, _, _, _, _) => a
          case _ => throw Diagnostic("Invalid lambda type")
        }
        s"{ val $function: ${target(v.tpe, env)} = box { ($x: ${target(argument, env)}) => " +
          s"${statements(term(v.body.get, env.bind(parameter, x)))} }; $function }"
    }

    /** Effekt boxed types are invariant. Elaborate source function subsumption
     * with eta adapters instead of expecting target annotations to widen boxes.
     * Evaluate the original expression once, before constructing its adapter.
     */
    private def coerce(code: String, from: Type, to: Type, env: Env): String = {
      if TypeEquality.same(from, to) || TypeEquality.same(Contracts.erase(from), Contracts.erase(to)) then code
      else (from, to) match {
        case (Type.FixedFunction(a, r, cs, region, p), Type.FixedFunction(b, s, ds, target, q)) =>
          coerce(code, Type.Function(a, r, cs, region, p), Type.Function(b, s, ds, target, q), env)
        case (Type.Continuation(a, r, region, cs, p), Type.FixedFunction(b, s, ds, target, q)) =>
          coerce(code, Type.Function(a, r, cs, Some(region), p), Type.Function(b, s, ds, target, q), env)
        case (Type.Function(a, r, _, _, _, _) , Type.Function(b, s, cs, _, _, _) ) =>
          val original = fresh("Original")
          val arg = fresh("AdapterArg")
          val converted = fresh("ConvertedArg")
          val result = fresh("AdapterResult")
          val adapted = fresh("Adapter")
          val capSet = (cs.toList.sorted.map(env.caps) :+ "io").distinct.mkString("{", ", ", "}")
          val argument = coerce(arg, b, a, env)
          val returned = coerce(result, r.value, s.value, env)
          s"{ val $original = $code; val $adapted: ${target(to, env)} = " +
            s"box $capSet { ($arg: ${target(b, env)}) => val $converted = $argument; " +
            s"val $result = $original($converted); ${statements(returned)} }; $adapted }"
        case (Type.CapabilityPoly(a, ops, body, _, _, _), Type.CapabilityPoly(b, _, expected, captures, _, _)) =>
          val original = fresh("Original")
          val cap = fresh("AdapterCap")
          val result = fresh("AdapterResult")
          val adapted = fresh("Adapter")
          val capType = interface(ops, env)
          val inner = env.withCapability(a, cap, capType).withCapability(b, cap, capType)
          val returned = coerce(result, body, expected, inner)
          val cs = (captures.toList.sorted.map(env.caps) :+ "io").distinct.mkString("{", ", ", "}")
          s"{ val $original = $code; val $adapted: ${target(to, env)} = " +
            s"box $cs { () {$cap: $capType} => val $result = $original(){$cap}; ${statements(returned)} }; $adapted }"
        case (Type.Variable(a), Type.Variable(b)) if env.types.get(a).nonEmpty && env.types.get(a) == env.types.get(b) => code
        case (Type.Pair(a, b), Type.Pair(c, d)) =>
          val original = fresh("OriginalPair")
          val first = fresh("CoercedFirst")
          val second = fresh("CoercedSecond")
          s"{ val $original = $code; val $first = ${coerce(s"$original.atmFirst", a, c, env)}; " +
            s"val $second = ${coerce(s"$original.atmSecond", b, d, env)}; AtmPair($first, $second) }"
        case (Type.Capability(a), Type.Capability(b)) if env.caps(a) == env.caps(b) => code
        case (Type.EffectPoly(_, body, _), Type.EffectPoly(_, expected, _)) => coerce(code, body, expected, env)
        case (Type.PredicatePoly(_, _, body), Type.PredicatePoly(_, _, expected)) => coerce(code, body, expected, env)
        case (Type.TypePoly(a, body), Type.TypePoly(b, expected)) =>
          val original = fresh("Original")
          val parameter = fresh("Type")
          val adapter = fresh("PolyAdapter")
          val inner = env.copy(types = env.types.updated(a, parameter).updated(b, parameter))
          val result = coerce(s"$original[$parameter]()", body, expected, inner)
          s"{ val $original = $code; def $adapter[$parameter](): ${target(expected, inner)} = { ${statements(result)} }; box $adapter }"
        case _ => throw Diagnostic(s"Missing checked coercion from ${show(from)} to ${show(to)}")
      }
    }

    private def term(t: CheckedTerm, env: Env): String = {
      val code = rawTerm(t, env)
      t.coercedFrom.fold(code)(from => coerce(code, from, t.tpe.value, env))
    }

    private def rawTerm(t: CheckedTerm, env: Env): String = t.source match {
      case Term.TypeAlias(_, _, _) | Term.AnnotationAlias(_, _, _) => term(t.children.head, env)
      case Term.Return(_) => value(t.values.head, env)
      case Term.Assert(_) => "()"
      case Term.Ascribe(_, _) => term(t.children.head, env)
      case _: Term.InferredHandle => throw Diagnostic("Unresolved handler result annotation")
      case _: Term.ExplicitReturn => throw Diagnostic("Unresolved explicit return clause")
      case Term.LetRec(name, parameter, _, _, _, _, _, _) =>
        val function = fresh("Recursive")
        val argument = fresh("Arg")
        val boxed = fresh("RecursiveValue")
        val (arg, result) = t.values.head.tpe match {
          case Type.Function(a, r, _, _, _, _) => (a, r)
          case Type.FixedFunction(a, r, _, _, _) => (a, r)
          case _ => throw Diagnostic("Invalid recursive function type")
        }
        val scope = env.bind(name, s"box $function").copy(recursive = env.recursive.updated(name, function))
        val definition = term(t.children.head, scope.bind(parameter, argument))
        s"{ def $function($argument: ${target(arg, env)}): ${target(result.value, env)} = { ${statements(definition)} }; " +
          s"val $boxed = box $function; ${statements(term(t.children(1), scope.copy(values = scope.values.updated(name, boxed))))} }"
      case Term.Let(name, _, _) =>
        val x = fresh("Val")
        s"{ val $x = ${term(t.children.head, env)}; ${statements(term(t.children(1), env.bind(name, x)))} }"
      case Term.Apply(function, _) =>
        val f = fresh("Call")
        val arg = fresh("CallArg")
        val parameter = t.values.head.tpe match {
          case Type.Function(p, _, _, _, _, _) => p
          case Type.FixedFunction(p, _, _, _, _) => p
          case Type.Continuation(p, _, _, _, _) => p
          case _ => throw Diagnostic("Invalid elaborated application")
        }
        val argument = coerce(value(t.values(1), env), t.values(1).tpe, parameter, env)
        function match {
          case Value.Variable(name) if env.recursive.contains(name) =>
            s"{ val $arg = $argument; ${env.recursive(name)}($arg) }"
          case _ => s"{ val $f = ${value(t.values.head, env)}; val $arg = $argument; $f($arg) }"
        }
      case Term.If(_, _, _) =>
        val condition = fresh("Condition")
        s"{ val $condition = ${value(t.values.head, env)}; " +
          s"if ($condition) { ${statements(term(t.children.head, env))} } else { ${statements(term(t.children(1), env))} } }"
      case Term.Match(_, cases) =>
        val scrutinee = fresh("Matched")
        val branches = cases.zip(t.children).map { (c, body) =>
          val params = c.parameters.map(_ => fresh("Pattern"))
          val scope = c.parameters.zip(params).foldLeft(env) { case (e, (n, v)) => e.bind(n, v) }
          "case AtmCtor" + c.constructor + params.mkString("(", ", ", ")") + " => " + statements(term(body, scope))
        }.mkString("\n")
        s"{ val $scrutinee = ${value(t.values.head, env)}; $scrutinee match { $branches } }"
      case _: Term.AnnotatedPerform | _: Term.SingleAnnotatedPerform => throw Diagnostic("Unresolved operation annotation")
      case Term.Perform(cap, operation, _) =>
        val receiver = env.values.getOrElse(cap, env.caps.getOrElse(cap,
          throw Diagnostic(s"Missing target capability $cap")))
        val argument = coerce(value(t.values.head, env), t.values.head.tpe, t.operationArgument.get, env)
        val arg = fresh("OperationArg")
        s"{ val $arg = $argument; $receiver.$operation($arg) }"
      case Term.Handle(cap, _, _, _, returned, _) =>
        val id = fresh("Handler")
        val boxName = s"AtmAnswer$id"
        val normalTag = s"AtmNormal$id"
        val capName = fresh("Cap")
        val body = t.children.head
        val onReturn = t.children(1)
        val Effect.Change(_, finalAnswer) = body.tpe.effect: @unchecked
        val answers = (List(onReturn.tpe.value, finalAnswer.value) ++
          t.operations.flatMap(op => List(op.source.initialAnswer.value, op.source.finalAnswer.value)))
          .foldLeft(List.empty[Type]) { (known, tpe) =>
            val erased = Contracts.erase(tpe)
            if known.exists(TypeEquality.same(_, erased)) then known else known :+ erased
          }
        val fields = body.tpe.value :: answers
        val fieldTypes = fields.map(target(_, env))
        val params = fields.indices.map(i => s"A$i").toList
        val args = fieldTypes.mkString("[", ", ", "]")
        val tags = answers.indices.map(i => s"AtmCase${id}x$i").toList
        val constructors = (s"$normalTag(value: A0)" :: tags.zipWithIndex.map((tag, i) => s"$tag(value: A${i + 1})"))
        declarations += s"type $boxName${params.mkString("[", ", ", "]")} { ${constructors.mkString("; ")} }"
        val capType = interface(t.operations.map(_.source.signature), env)

        def pack(tpe: Type, code: String): String = {
          val index = answers.indexWhere(TypeEquality.same(_, Contracts.erase(tpe)))
          if index < 0 then throw Diagnostic(s"Missing answer representation for ${show(tpe)}")
          val v = fresh("Packed")
          s"{ val $v = $code; ${tags(index)}$args($v) }"
        }
        def decode(tpe: Type, wire: String): String = {
          val v = fresh("Unpacked")
          val x = fresh("Returned")
          val result = fresh("Decoded")
          val resultType = target(tpe, env)
          // Instantiate the failure branch explicitly. A polymorphic answer
          // otherwise leaves native bottom-coercion capture variables unresolved.
          def impossible(reason: String): String = s"atmImpossible[$resultType](\"ATM lowering invariant: $reason\")"
          val normal = if TypeEquality.same(Contracts.erase(onReturn.tpe.value), Contracts.erase(tpe)) then term(onReturn, env.bind(returned, x))
            else impossible("unexpected normal return")
          val branches = s"case $normalTag($x) => ${statements(normal)}" :: tags.zip(answers).map { (tag, answer) =>
            val body = if TypeEquality.same(Contracts.erase(answer), Contracts.erase(tpe)) then v else impossible("unexpected answer tag")
            s"case $tag($v) => $body"
          }
          s"{ val $result: $resultType = $wire match { ${branches.mkString("\n")} }; $result }"
        }

        val opBodies = t.operations.map { checked =>
          val op = checked.source
          val a = fresh("OpArg")
          val k = fresh("Resume")
          val resumed = fresh("Resumed")
          val argument = fresh("ResumeArg")
          val wrapper = s"box { ($argument: ${target(op.result, env)}) => " +
            s"val $resumed = resume($argument); ${statements(decode(op.initialAnswer.value, resumed))} }"
          val clause = term(checked.body, env.bind(op.parameter, a).bind(op.continuation, k))
          s"def ${op.name}($a) = { val $k = $wrapper; ${statements(pack(op.finalAnswer.value, clause))} }"
        }
        val result = fresh("Wire")
        val returnedValue = fresh("BodyResult")
        val inner = env.withCapability(cap, capName, capType)
        s"{ val $result: $boxName$args = try { val $returnedValue = ${term(body, inner)}; " +
          s"$normalTag$args($returnedValue) } with $capName: $capType " +
          s"{ ${opBodies.mkString("\n")} }; ${statements(decode(finalAnswer.value, result))} }"
    }

    // Source Int denotes mathematical integers. The existing JS Int is Number;
    // silently reusing it would invalidate integer refinement specifications.
    private val integerPrelude = """record AtmPair[A, B](atmFirst: A, atmSecond: B)
      |extern io def atmImpossible[A](message: String): A = js "(() => { throw new Error(${message}); })()"
      |extern type AtmInteger
      |extern pure def atmInteger(decimal: String): AtmInteger = js "BigInt(${decimal})"
      |extern pure def atmAdd(x: AtmInteger, y: AtmInteger): AtmInteger = js "(${x} + ${y})"
      |extern pure def atmSubtract(x: AtmInteger, y: AtmInteger): AtmInteger = js "(${x} - ${y})"
      |extern pure def atmMultiply(x: AtmInteger, y: AtmInteger): AtmInteger = js "(${x} * ${y})"
      |extern pure def atmDivide(x: AtmInteger, y: AtmInteger): AtmInteger = js "(${x} / ${y})"
      |extern pure def atmRemainder(x: AtmInteger, y: AtmInteger): AtmInteger = js "(${x} % ${y})"
      |extern pure def atmEqual(x: AtmInteger, y: AtmInteger): Bool = js "(${x} === ${y})"
      |extern pure def atmLess(x: AtmInteger, y: AtmInteger): Bool = js "(${x} < ${y})"
      |extern pure def atmLessEqual(x: AtmInteger, y: AtmInteger): Bool = js "(${x} <= ${y})"
      |extern pure def atmGreater(x: AtmInteger, y: AtmInteger): Bool = js "(${x} > ${y})"
      |extern pure def atmGreaterEqual(x: AtmInteger, y: AtmInteger): Bool = js "(${x} >= ${y})"
      |extern pure def atmZero(x: AtmInteger): Bool = js "(${x} === 0n)"
      |extern pure def atmShowInteger(x: AtmInteger): String = js "${x}.toString()"
      |""".stripMargin
  }
}

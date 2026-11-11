package effekt.atm

import Syntax.*
import Annotations.{Kind, Argument, Proof}
import Evidence.{Distance, AnswerContext}

/** Effekt-like surface syntax for the experimental ATM language.
 * Parsing only desugars syntax; the existing source checker owns all typing.
 */
object Parser {
  def parse(input: String): Either[Diagnostic, Program] = try {
    Right(new Frontend(input).program())
  } catch { case d: Diagnostic => Left(d) }

  private case class Token(text: String, offset: Int)

  private class Frontend(input: String) {
    private def error(message: String, offset: Int): Nothing = {
      val prefix = input.take(offset)
      val line = prefix.count(_ == '\n') + 1
      val column = offset - prefix.lastIndexOf('\n')
      throw Diagnostic(s"$line:$column: $message", DiagnosticKind.ParseError)
    }

    private val tokens: Vector[Token] = {
      val result = Vector.newBuilder[Token]
      var i = 0
      while i < input.length do {
        val start = i
        if input(i).isWhitespace then i += 1
        else if input.startsWith("//", i) then {
          while i < input.length && input(i) != '\n' do i += 1
        } else if input.startsWith("/*", i) then {
          i += 2
          var depth = 1
          while i < input.length && depth > 0 do {
            if input.startsWith("/*", i) then { depth += 1; i += 2 }
            else if input.startsWith("*/", i) then { depth -= 1; i += 2 }
            else i += 1
          }
          if depth != 0 then error("Unterminated block comment", start)
        } else if input(i).isLetter || input(i) == '_' then {
          i += 1
          while i < input.length && (input(i).isLetterOrDigit || "_!?$".contains(input(i))) do i += 1
          result += Token(input.substring(start, i), start)
        } else if input(i).isDigit then {
          i += 1
          while i < input.length && input(i).isDigit do i += 1
          result += Token(input.substring(start, i), start)
        } else if List("=>", "==", "!=", "<=", ">=", "&&", "||").exists(input.startsWith(_, i)) then {
          result += Token(input.substring(i, i + 2), i); i += 2
        } else if "(){}[]:;,.=/+-!*%<>|".contains(input(i)) then {
          result += Token(input(i).toString, i); i += 1
        } else error(s"Unexpected character '${input(i)}'", i)
      }
      result += Token("<end>", input.length)
      result.result()
    }
    private var position = 0
    private def current: String = tokens(position).text
    private def fail(message: String): Nothing = error(message, tokens(position).offset)
    private def take(): String = { val text = current; position += 1; text }
    private def accept(text: String): Boolean = if current == text then { take(); true } else false
    private def expect(text: String): Unit =
      if !accept(text) then fail(s"Expected '$text', found '$current'")
    private val keywords = Set("def", "main", "val", "return", "if", "else", "try", "with", "resume",
      "fun", "capfun", "effectfun", "forall", "cap", "effect", "here", "at", "pure", "any",
      "repeatable", "true", "false", "token", "zero", "add", "not",
      "interface", "data", "measure", "match", "case", "type", "pred", "typefun", "predfun", "assert", "rec", "then", "fst", "snd")
    private def identifier(): String = {
      if !current.matches("[a-zA-Z_][a-zA-Z0-9_!?$]*") || keywords(current) then
        fail(s"Expected an identifier, found '$current'")
      take()
    }

    // Primitive spellings are ordinary member names in native Effekt.
    private def operationName(): String =
      if Set("add", "not", "fst", "snd")(current) then take() else identifier()

    private def newlineBeforeCurrent: Boolean = position > 0 &&
      input.substring(tokens(position - 1).offset + tokens(position - 1).text.length,
        tokens(position).offset).contains('\n')
    private def separator(): Unit =
      if !accept(";") && current != "}" && current != "<end>" && !newlineBeforeCurrent then fail("Expected ';' or a newline")

    private var generated = 0
    private def fresh(prefix: String): String = { generated += 1; s"$prefix#$generated" }
    private var bindings = scala.collection.mutable.ListBuffer.empty[(String, Term)]
    // Keep effects inside their expression/branch, in left-to-right order.
    // Generated names cannot collide with source identifiers.
    private def expression(body: => Term): Term = {
      val outer = bindings
      bindings = scala.collection.mutable.ListBuffer.empty
      try {
        val result = body
        bindings.toList.foldRight(result) { case ((name, binding), rest) => rest match {
          case Term.Return(Value.Variable(n)) if n == name => binding
          case _ => Term.Let(name, binding, rest)
        }}
      } finally bindings = outer
    }
    private def atomize(term: Term): Value = term match {
      case Term.Return(v) => v
      case _ =>
        val name = fresh("argument")
        bindings += name -> term
        Value.Variable(name)
    }
    private def pureValue(): Value = expression(Term.Return(value())) match {
      case Term.Return(v) => v
      case _ => fail("A polymorphic abstraction body must be a value; put computations inside a function")
    }

    private case class Parameters(fields: List[(String, Option[Type])], binder: String) {
      def size: Int = fields.size
      def argument: Type = Surface.tuple(fields.map { (n, t) => t.getOrElse(fail(s"Parameter $n needs a type annotation")) })
      def bind(body: Term): Term = if size <= 1 then body else
        fields.zipWithIndex.foldRight(body) { case (((name, _), index), rest) =>
          Term.Let(name, Term.Return(Surface.projection(Value.Variable(binder), index, size)), rest)
        }
      def result(c: Computation): Computation = if size <= 1 then c else
        fields.zipWithIndex.foldLeft(c) { case (t, ((name, _), index)) =>
          Contracts.substitute(t, name, Surface.projection(Logic.Expr.Variable(binder), index, size))
        }
      def result(t: Type): Type = result(Computation(t)).value
    }
    private def parameters(required: Boolean = false, optionalParens: Boolean = false): Parameters = {
      val fields = scala.collection.mutable.ListBuffer.empty[(String, Option[Type])]
      if !optionalParens || current == "(" then {
        expect("(")
        def parameter(): Unit = {
          val n = identifier()
          val annotation = if accept(":") then Some(tpe()) else None
          if required && annotation.isEmpty then fail(s"Parameter $n needs a type annotation")
          fields += n -> annotation
        }
        if current != ")" then { parameter(); while accept(",") do parameter() }
        expect(")")
      }
      if fields.map(_._1).distinct.size != fields.size then fail("Duplicate parameter")
      Parameters(fields.toList, if fields.size == 1 then fields.head._1 else fresh("parameters"))
    }

    private def typeParameters(): List[String] = {
      val names = scala.collection.mutable.ListBuffer.empty[String]
      if accept("[") then {
        names += identifier(); while accept(",") do names += identifier()
        expect("]")
      }
      if names.distinct.size != names.size then fail("Duplicate type parameter")
      names.toList
    }
    private def typeArguments(): List[Type] = {
      val args = scala.collection.mutable.ListBuffer.empty[Type]
      if accept("[") then {
        args += tpe(); while accept(",") do args += tpe()
        expect("]")
      }
      args.toList
    }
    private case class Definition(name: String, wrap: Term => Term, dependencies: Set[String])
    private def definition(): Definition = {
      val recursive = accept("rec")
      val local = accept("local"); val region = if local then localRegion() else None
      if recursive && region.nonEmpty then fail("A recursive local definition uses its enclosing lexical region")
      val name = identifier()
      val types = typeParameters()
      val params = parameters(required = true)
      if current == "{" then fail("Native block inference is unsupported; use explicit capability, region, computation, and evidence parameters")
      val param = params.binder; val arg = params.argument
      val res = if accept(":") then Some(params.result(computation())) else None
      expect("=")
      val body = params.bind(term())
      accept(";")
      val free = Surface.free(body) - param
      if recursive || free(name) then {
        if types.nonEmpty then fail("Recursive polymorphic definitions are not supported yet")
        val result = res.getOrElse(fail("A recursive definition requires a result annotation"))
        Definition(name, rest => Term.LetRec(name, param, arg, result, body, rest, local), free - name)
      } else {
        val function = if local then Value.LocalLambda(param, Some(arg), res, body, region)
          else res.fold[Value](Value.InferredLambda(param, arg, body))(Value.Lambda(param, arg, _, body))
        val value = types.foldRight(function)(Value.TypeLambda.apply)
        Definition(name, rest => Term.Let(name, Term.Return(value), rest), free)
      }
    }

    private var interfaces = Map.empty[String, InterfaceDeclaration]
    private def interfaceDeclaration(): InterfaceDeclaration = {
      val name = identifier()
      val types = typeParameters()
      if interfaces.contains(name) then fail(s"Duplicate interface $name")
      expect("{")
      val ops = scala.collection.mutable.ListBuffer.empty[InterfaceOperation]
      while accept("def") do {
        val operation = operationName()
        val params = parameters(required = true, optionalParens = true)
        expect(":"); val (result, returned) = operationResult()
        if ops.exists(_.name == operation) then fail(s"Duplicate operation $operation in interface $name")
        ops += InterfaceOperation(operation, Option.when(params.size > 0)(params.binder), params.argument, params.result(result), returned, params.size)
        if current != "def" then separator()
      }
      expect("}"); accept(";")
      InterfaceDeclaration(name, ops.toList, types)
    }

    private var constructorNames = Set.empty[String]
    private var dataNames = Set.empty[String]
    // Datatype field contracts may refer to measures declared after the data.
    // The measure checker still rejects forward/mutual recursion in definitions.
    private var measureNames = tokens.sliding(2).collect {
      case Vector(Token("measure", _), Token(name, _)) => name
    }.toSet
    private def dataDeclaration(): DataDeclaration = {
      val name = identifier()
      if !name.head.isUpper then fail("A datatype name must start with an uppercase letter")
      expect("{")
      val constructors = scala.collection.mutable.ListBuffer.empty[DataConstructor]
      while current != "}" do {
        val ctor = identifier(); expect("(")
        val fields = scala.collection.mutable.ListBuffer.empty[(String, Type)]
        def field(): Unit = { val n = identifier(); expect(":"); fields += ((n, tpe())) }
        if current != ")" then { field(); while accept(",") do field() }
        expect(")"); constructors += DataConstructor(ctor, fields.toList)
        if current != "}" then separator()
      }
      expect("}"); accept(";")
      DataDeclaration(name, constructors.toList)
    }
    def program(): Program = {
      val declarations = scala.collection.mutable.ListBuffer.empty[DataDeclaration]
      val aliases = scala.collection.mutable.ListBuffer.empty[(String, Type)]
      while Set("data", "type", "interface")(current) do {
        if current == "type" && tokens.lift(position + 2).exists(_.text == "=") then {
          take(); val name = identifier(); expect("="); aliases += name -> tpe(); accept(";")
        } else if accept("data") || accept("type") then declarations += dataDeclaration()
        else {
          expect("interface"); val declaration = interfaceDeclaration()
          interfaces += declaration.name -> declaration
        }
      }
      constructorNames = declarations.flatMap(_.constructors.map(_.name)).toSet
      dataNames = declarations.map(_.name).toSet
      val measures = scala.collection.mutable.ListBuffer.empty[Logic.Measure]
      while accept("measure") do {
        val name = identifier(); measureNames += name; expect("(")
        val params = scala.collection.mutable.ListBuffer.empty[(String, Logic.Sort)]
        def param(): Unit = { val n = identifier(); expect(":"); params += ((n, sort())) }
        if current != ")" then { param(); while accept(",") do param() }
        expect(")"); expect(":"); val result = sort(); expect("{")
        val cases = scala.collection.mutable.ListBuffer.empty[Logic.MeasureCase]
        while accept("case") do {
          val constructor = identifier(); expect("(")
          val fields = scala.collection.mutable.ListBuffer.empty[String]
          if current != ")" then { fields += identifier(); while accept(",") do fields += identifier() }
          expect(")"); expect("=>"); val body = predicate(); expect(";")
          cases += Logic.MeasureCase(constructor, fields.toList, body)
        }
        expect("}"); measures += Logic.Measure(name, params.toList, result, cases.toList)
      }
      val definitions = scala.collection.mutable.ListBuffer.empty[Definition]
      var main: Option[(Option[Computation], Term)] = None
      while current != "<end>" do {
        if accept("def") then {
          if accept("main") then {
            if main.nonEmpty then fail("Duplicate main definition")
            expect("("); expect(")")
            val result = if accept(":") then Some(computation()) else None
            expect("="); main = Some((result, term())); accept(";")
          } else definitions += definition()
        } else if accept("val") then {
          val name = identifier()
          val annotation = if accept(":") then Some(computation()) else None
          expect("="); val raw = term(); separator()
          val value = annotation.fold(raw)(Term.Ascribe(raw, _))
          definitions += Definition(name, rest => Term.Let(name, value, rest), Surface.free(value))
        } else fail("Expected a top-level definition")
      }
      val (result, body) = main.getOrElse(fail("A program needs def main()"))
      if definitions.map(_.name).distinct.size != definitions.size then fail("Duplicate top-level definition")
      // Order acyclic definitions by dependencies. Mutual recursion needs a
      // recursive group in the core and is deliberately not approximated.
      var pending = definitions.toList
      val ordered = scala.collection.mutable.ListBuffer.empty[Definition]
      while pending.nonEmpty do {
        val names = pending.map(_.name).toSet
        val next = pending.find(d => (d.dependencies intersect names).isEmpty).getOrElse(
          fail("Mutually recursive top-level definitions are not supported yet"))
        ordered += next; pending = pending.filterNot(_.name == next.name)
      }
      val program = ordered.toList.foldRight(body)((d, b) => d.wrap(b))
      Program(result.getOrElse(Computation(Type.Unit)), program, declarations.toList, measures.toList,
        interfaces.values.toList.sortBy(_.name), inferResult = result.isEmpty, typeAliases = aliases.toList)
    }

    private def captures(): Set[String] = {
      expect("{")
      val names = scala.collection.mutable.Set.empty[String]
      if current != "}" then {
        names += identifier()
        while accept(",") do names += identifier()
      }
      expect("}"); names.toSet
    }

    // At the start of an answer transition, the outer => separates endpoints.
    // A function used as that endpoint must be grouped: (((Int) => Int) => Bool).
    // Arrows inside the group remain ordinary function arrows.
    private def sort(): Logic.Sort = {
      if accept("(") then { val a = sort(); expect(","); val b = sort(); expect(")"); Logic.Sort.Pair(a, b) }
      else if accept("Int") then Logic.Sort.Int
      else if accept("Bool") then Logic.Sort.Bool
      else if accept("Unit") then Logic.Sort.Unit
      else if dataNames(current) then Logic.Sort.Data(take())
      else fail("A refinement parameter needs a declared logical sort")
    }
    private def predicateSorts(): List[Logic.Sort] = {
      expect("(")
      val args = scala.collection.mutable.ListBuffer.empty[Logic.Sort]
      if current != ")" then { args += sort(); while accept(",") do args += sort() }
      expect(")"); args.toList
    }
    private val precedence = Map("=>" -> 1, "||" -> 2, "&&" -> 3, "==" -> 4, "!=" -> 4,
      "<" -> 5, "<=" -> 5, ">" -> 5, ">=" -> 5, "+" -> 6, "-" -> 6, "*" -> 7, "/" -> 7, "%" -> 7)
    private def predicate(minimum: Int = 1): Logic.Expr = {
      import Logic.Expr
      var left: Logic.Expr = current match {
        case "!" => take(); Expr.App("!", List(predicate(8)))
        case "-" => take(); Expr.App("neg", List(predicate(8)))
        case op @ ("fst" | "snd") => take(); expect("("); val x = predicate(); expect(")"); Expr.App(op, List(x))
        case "true" => take(); Expr.Boolean(true)
        case "false" => take(); Expr.Boolean(false)
        case "(" =>
          take()
          if accept(")") then Expr.Unit else {
            val p = predicate()
            val result = if accept(",") then Expr.App("pair", List(p, predicate())) else p
            expect(")"); result
          }
        case "forall" =>
          take(); val name = identifier(); expect(":"); val domain = sort(); expect(".")
          Expr.Forall(name, domain, predicate())
        case "if" =>
          take(); val c = predicate(); expect("then"); val a = predicate(); expect("else")
          Expr.App("ite", List(c, a, predicate()))
        case n if n.matches("[0-9]+") => take(); Expr.Integer(BigInt(n))
        case _ =>
          val name = identifier()
          if accept("(") then {
            val args = scala.collection.mutable.ListBuffer.empty[Logic.Expr]
            if current != ")" then { args += predicate(); while accept(",") do args += predicate() }
            expect(")")
            if constructorNames(name) then Datatypes.expression(name, args.toList)
            else if measureNames(name) then Expr.App("measure:" + name, args.toList)
            else Expr.Predicate(name, args.toList)
          } else Expr.Variable(name)
      }
      while precedence.get(current).exists(_ >= minimum) do {
        val op = take(); val right = predicate(precedence(op) + 1)
        left = Expr.App(op, List(left, right))
      }
      left
    }
    private def regionName(): String = if current == "here" || current == "top" then take() else identifier()
    private def distance(): Distance = {
      def atom(): Distance = if accept("(") then { val d = distance(); expect(")"); d }
        else if current.matches("[0-9]+") then {
          val n = BigInt(take()); if !n.isValidInt then fail("Distance literal is too large")
          Distance.Numeral(n.toInt)
        } else Distance.Variable(identifier())
      var d = atom()
      while accept("+") do d = Distance.Plus(d, atom())
      d
    }
    private def proof(): Proof = {
      def atom(): Proof = current match {
        case "refl" | "zero" | "0" =>
          take(); Proof.Refl(if accept("(") then { val r = regionName(); expect(")"); r } else "here")
        case "step" => take(); expect("("); val r = regionName(); expect(")"); Proof.Step(r)
        case "path" => take(); expect("("); val r = regionName(); expect(")"); Proof.Path(r)
        case "(" => take(); val e = proof(); expect(")"); e
        case _ => Proof.Variable(identifier())
      }
      var e = atom(); while accept("+") do e = Proof.Compose(e, atom()); e
    }
    private def context(): AnswerContext = {
      val dependent = current == "(" && tokens.lift(position + 2).exists(_.text == ":")
      if accept("[") then { expect("]"); AnswerContext.Hole }
      else if !dependent && accept("(") then { val c = context(); expect(")"); c }
      else if accept("layer") then {
        expect("("); val v = tpe(); expect(","); val i = computation(); expect(",")
        val rest = context(); expect(")"); AnswerContext.Layer(v, i, rest, None)
      } else if !dependent && current.matches("[a-zA-Z_][a-zA-Z0-9_]*") &&
          !tokens.lift(position + 1).exists(_.text == "/") then {
        val name = identifier(); val c = AnswerContext.Variable(name)
        if accept("[") then { val rest = context(); expect("]"); Annotations.contextCompose(c, rest) } else c
      } else {
        val binder = if dependent then { take(); val n = identifier(); expect(":"); Some(n) } else None
        val v = tpe(allowArrow = false); if dependent then expect(")")
        expect("/"); expect("("); val i = computation(allowArrow = false); expect("=>")
        val rest = context(); expect(")"); AnswerContext.Layer(v, i, rest, binder)
      }
    }
    private def kind(): Kind = take() match {
      case "Reg" => Kind.Region
      case "Dist" => Kind.Distance
      case "Cty" => Kind.Computation
      case "ATC" => expect("("); val d = distance(); expect(")"); Kind.Context(d)
      case "Ev" =>
        expect("("); val from = regionName(); expect(","); val d = distance(); expect(",")
        val to = regionName(); expect(")"); Kind.Evidence(from, d, to)
      case other => fail(s"Unknown annotation kind $other; use Reg, Dist, Cty, ATC(d), or Ev(from, d, to)")
    }
    private def annotation(tag: String): Argument = tag match {
      case "region" => Argument.Region(regionName())
      case "distance" => Argument.Distance(distance())
      case "evidence" => Argument.Evidence(proof())
      case "atc" => Argument.Context(context())
      case "cty" => Argument.Computation(computation())
      case _ => fail(s"Unknown annotation argument $tag")
    }
    private def predicateArgument(): PolymorphicArgument = {
      expect("(")
      val params = scala.collection.mutable.ListBuffer.empty[(String, Logic.Sort)]
      def param(): Unit = { val name = identifier(); expect(":"); params += ((name, sort())) }
      if current != ")" then { param(); while accept(",") do param() }
      expect(")"); expect("=>")
      PolymorphicArgument.Predicate(params.toList, predicate())
    }
    private def polymorphicArgument(): List[PolymorphicArgument] = {
      val start = position
      var end = start
      var depth = 0
      while end < tokens.size && !(depth == 0 && Set(",", "]")(tokens(end).text)) do {
        tokens(end).text match {
          case "(" | "[" | "{" => depth += 1
          case ")" | "]" | "}" => depth -= 1
          case _ => ()
        }
        end += 1
      }
      if end == tokens.size || depth != 0 then fail("Unterminated polymorphic argument")
      // These grammars have no expression/binding side effects. Keep only
      // complete readings; checking selects one by the function's parameter.
      def attempt(parse: => PolymorphicArgument): Option[PolymorphicArgument] = {
        position = start
        try {
          val result = parse
          Option.when(position == end)(result)
        } catch { case d: Diagnostic if d.kind == DiagnosticKind.ParseError => None }
        finally position = start
      }
      val alternatives = List(
        attempt(PolymorphicArgument.ValueType(tpe())),
        attempt(PolymorphicArgument.Kind(Argument.Region(regionName()))),
        attempt(PolymorphicArgument.Kind(Argument.Distance(distance()))),
        attempt(PolymorphicArgument.Kind(Argument.Evidence(proof()))),
        attempt(PolymorphicArgument.Kind(Argument.Context(context()))),
        attempt(PolymorphicArgument.Kind(Argument.Computation(computation()))),
        attempt(PolymorphicArgument.Kind(Argument.Computation(
          Annotations.abstractComputation(ComputationForm.Variable(identifier()))))),
        attempt(PolymorphicArgument.AnswerEffect(effect())),
        attempt(predicateArgument())).flatten
      if alternatives.isEmpty then fail("Expected a type, region, distance, evidence, ATC, computation, effect, or predicate argument")
      position = end
      alternatives
    }
    private def taggedApplication(function: Value): Option[Value] = {
      val start = position
      val result = try {
        val application = current match {
          case "region" | "distance" | "evidence" | "atc" | "cty" =>
            Value.InstantiateKind(function, annotation(take()))
          case "type" => take(); Value.InstantiateType(function, tpe())
          case "effect" => take(); Value.InstantiateEffect(function, effect())
          case "pred" =>
            take()
            val PolymorphicArgument.Predicate(params, body) = predicateArgument(): @unchecked
            Value.InstantiatePredicate(function, params, body)
          case _ => return None
        }
        expect("]")
        Some(application)
      } catch { case d: Diagnostic if d.kind == DiagnosticKind.ParseError => None }
      // A tag word can itself be an alias, e.g. f[distance + 1] or f[atc[J]].
      if result.isEmpty then position = start
      result
    }
    private def localRegion(): Option[String] =
      if accept("[") then { val name = regionName(); expect("]"); Some(name) } else None
    private def tpe(allowArrow: Boolean = true): Type = {
      val base = current match {
        case "{" =>
          take(); val parameter = identifier(); expect(":"); val base = tpe(); expect("|")
          val formula = predicate(); expect("}"); Type.Refined(parameter, base, formula)
        case "local" =>
          take(); val region = localRegion(); expect("(")
          val parameter = if tokens.lift(position + 1).exists(_.text == ":") then {
            val name = identifier(); expect(":"); Some(name)
          } else None
          val arg = tpe(); expect(")"); expect("=>")
          Type.LocalFunction(arg, computation(), region = region, parameter = parameter)
        case "Int" => take(); Type.Int
        case "Bool" => take(); Type.Bool
        case "Unit" => take(); Type.Unit
        case "cap" => take(); expect("("); val name = identifier(); expect(")"); Type.Capability(name)
        case "(" =>
          take()
          val parameter = if tokens.lift(position + 1).exists(_.text == ":") then {
            val name = identifier(); expect(":"); Some(name)
          } else None
          val arguments = scala.collection.mutable.ListBuffer.empty[Type]
          if current != ")" then { arguments += tpe(); while accept(",") do arguments += tpe() }
          val argument = Surface.tuple(arguments.toList)
          expect(")")
          if allowArrow && accept("=>") then Type.Function(argument, computation(), parameter = parameter)
          else if parameter.nonEmpty then fail("A dependent parameter must be followed by a function arrow")
          else argument
        case "forall" =>
          take()
          if accept("cap") then {
            val scoped = accept("here")
            val name = identifier(); expect(":")
            val ops = signatures(); expect("."); expect("(")
            val body = tpe(); expect(")")
            Type.CapabilityPoly(name, ops, body, context = if scoped then Some("here") else None)
          } else if accept("type") then {
            val name = identifier(); expect("."); expect("("); val body = tpe(); expect(")")
            Type.TypePoly(name, body)
          } else if accept("pred") then {
            val name = identifier(); val args = predicateSorts(); expect("."); expect("(")
            val body = tpe(); expect(")"); Type.PredicatePoly(name, args, body)
          } else if current != "effect" then {
            val name = identifier(); expect(":"); val k = kind(); expect("."); expect("(")
            val body = tpe(); expect(")"); Type.KindPoly(name, k, body)
          } else {
            expect("effect")
            val name = identifier()
            val constraint = optionalBound()
            expect("."); expect("(")
            val body = tpe(); expect(")")
            Type.EffectPoly(name, body, constraint)
          }
        case name if name.matches("[A-Z][a-zA-Z0-9_]*") => take(); Type.Token(name)
        case _ => fail(s"Expected a value type, found '$current'")
      }
      if accept("at") then {
        val cs = captures()
        base match {
          case f: Type.Function => f.copy(captures = cs)
          case f: Type.LocalFunction => f.copy(captures = cs)
          case p: Type.CapabilityPoly if p.context.nonEmpty => p.copy(captures = cs)
          case _ => fail("Capture annotations require a function or a scoped capability abstraction")
        }
      } else base
    }
    private def computation(allowArrow: Boolean = true): Computation = {
      if accept("cty") then return Annotations.abstractComputation(ComputationForm.Variable(identifier()))
      if current != "local" && current.matches("[a-zA-Z_][a-zA-Z0-9_]*") && tokens.lift(position + 1).exists(_.text == "[") then {
        val c = AnswerContext.Variable(identifier()); expect("["); val arg = computation(); expect("]")
        return c.plug(arg)
      }
      val dependent = current == "(" && tokens.lift(position + 2).exists(_.text == ":") && {
        var i = position; var depth = 0
        while i < tokens.length && (i == position || depth > 0) do {
          if tokens(i).text == "(" then depth += 1
          if tokens(i).text == ")" then depth -= 1
          i += 1
        }
        tokens.lift(i).exists(_.text == "/")
      }
      val binder = if dependent then { take(); val n = identifier(); expect(":"); Some(n) } else None
      val result = tpe(allowArrow)
      if dependent then expect(")")
      Computation(result, if accept("/") then effect() else Effect.Pure, binder)
    }
    // Parentheses also group effect variables/sequences. Only an arrow at the
    // group's own level separates answer endpoints; arrows inside function
    // types, nested answer effects, or refinement predicates do not.
    private def startsAnswerTransition: Boolean = {
      var i = position + 1
      var depth = 0
      while i < tokens.length do {
        tokens(i).text match {
          case "=>" if depth == 0 => return true
          case ")" | ";" if depth == 0 => return false
          case "(" | "[" | "{" => depth += 1
          case ")" | "]" | "}" => depth -= 1
          case "<end>" => return false
          case _ => ()
        }
        i += 1
      }
      false
    }
    private def answerTransition(): (Computation, Computation) = {
      // Retain the old delimiters for archived regression fixtures.
      val close = if accept("[") then "]" else { expect("("); ")" }
      val initial = computation(allowArrow = false); expect("=>")
      val result = computation(); expect(close)
      (initial, result)
    }
    private def effect(): Effect = current match {
      case "pure" => take(); Effect.Pure
      case "[" =>
        val (initial, result) = answerTransition(); Effect.Change(initial, result)
      case "(" if startsAnswerTransition =>
        val (initial, result) = answerTransition(); Effect.Change(initial, result)
      case "(" =>
        take(); var result = effect()
        while accept(";") do result = Effect.Sequence(result, effect())
        expect(")"); result
      case _ => Effect.Variable(identifier())
    }
    private def optionalBound(): EffectBound = if !accept(":") then EffectBound.Any else {
      if accept("any") then EffectBound.Any
      else if accept("repeatable") then EffectBound.Repeatable
      else { expect("after"); expect("("); val prefix = effect(); expect(")"); EffectBound.After(prefix) }
    }
    private def operationResult(): (Type, Option[String]) = {
      if current == "(" && tokens.lift(position + 2).exists(_.text == ":") then {
        take(); val name = identifier(); expect(":"); val result = tpe(); expect(")")
        (result, Some(name))
      } else (tpe(), None)
    }
    private def signature(): Signature = {
      expect("def"); val name = identifier(); expect("(")
      val parameter = if current != ")" && tokens.lift(position + 1).exists(_.text == ":") then {
        val name = identifier(); expect(":"); Some(name)
      } else None
      val argument = if current == ")" then Type.Unit else tpe()
      expect(")"); expect(":"); val (result, returned) = operationResult(); expect("/")
      val (initial, finalAnswer) = answerTransition()
      expect(";")
      Signature(name, argument, result, initial, finalAnswer, parameter, returned)
    }
    private def signatures(): List[Signature] = {
      expect("{")
      val result = scala.collection.mutable.ListBuffer.empty[Signature]
      while current != "}" && current != "<end>" do result += signature()
      expect("}"); result.toList
    }

    private def function(): Value = {
      val local = accept("local"); val region = if local then localRegion() else None
      val params = parameters()
      val argument = if params.fields.forall(_._2.nonEmpty) then Some(params.argument)
        else if params.size == 1 then None else fail("Multiple lambda parameters need type annotations")
      val result = if accept(":") then Some(params.result(computation())) else None
      val body = params.bind(block())
      if local then Value.LocalLambda(params.binder, argument, result, body, region) else (argument, result) match {
        case (Some(a), Some(r)) => Value.Lambda(params.binder, a, r, body)
        case (Some(a), None) => Value.InferredLambda(params.binder, a, body)
        case (None, r) => Value.UnannotatedLambda(params.binder, r, body)
      }
    }
    private def atom(): Value = current match {
      case "resume" => take(); Value.Variable("resume")
      case "true" => take(); Value.Boolean(true)
      case "false" => take(); Value.Boolean(false)
      case "-" =>
        take()
        if current.matches("[0-9]+") then Value.Integer(-BigInt(take()))
        else Value.Primitive("sub", List(Value.Integer(0), atom()))
      case n if n.matches("[0-9]+") => take(); Value.Integer(BigInt(n))
      case "(" =>
        take()
        if accept(")") then Value.Unit
        else {
          val v = atomize(term())
          val result = if accept(",") then Value.Primitive("pair", List(v, atomize(term()))) else v
          expect(")"); result
        }
      case "cap" => take(); expect("("); val name = identifier(); expect(")"); Value.Capability(name)
      case "token" => take(); expect("("); val name = identifier(); expect(")"); Value.Token(name)
      case "fun" => take(); if current == "[" then polymorphicFunction() else function()
      case "capfun" =>
        take(); val scoped = accept("here"); expect("("); val name = identifier(); expect(":")
        val ops = signatures(); expect(")"); expect("{"); val body = pureValue(); expect("}")
        Value.CapabilityLambda(name, ops, body, scoped)
      case "typefun" => take(); polymorphicFunction()
      case "predfun" =>
        take(); expect("["); val name = identifier(); val args = predicateSorts(); expect("]"); expect("{")
        val body = pureValue(); expect("}"); Value.PredicateLambda(name, args, body)
      case "effectfun" =>
        take(); expect("["); val name = identifier(); val constraint = optionalBound(); expect("]")
        expect("{"); val body = pureValue(); expect("}"); Value.EffectLambda(name, body, constraint)
      case name @ ("zero" | "add" | "not" | "fst" | "snd") =>
        take(); expect("(")
        val args = scala.collection.mutable.ListBuffer.empty[Value]
        if current != ")" then {
          args += atomize(term())
          while accept(",") do args += atomize(term())
        }
        expect(")"); Value.Primitive(name, args.toList)
      case name if constructorNames(name) =>
        take(); expect("(")
        val args = scala.collection.mutable.ListBuffer.empty[Value]
        if current != ")" then { args += atomize(term()); while accept(",") do args += atomize(term()) }
        expect(")"); Value.Constructor(name, args.toList)
      case _ => Value.Variable(identifier())
    }
    private def polymorphicFunction(): Value = {
      expect("[")
      val params = scala.collection.mutable.ListBuffer.empty[(String, Option[Kind])]
      def parameter(): Unit = { val n = identifier(); params += ((n, if accept(":") then Some(kind()) else None)) }
      parameter(); while accept(",") do parameter()
      if params.map(_._1).distinct.size != params.size then fail("Duplicate type abstraction parameter")
      expect("]")
      val body = if accept("{") then { val b = pureValue(); expect("}"); b } else function()
      params.toList.foldRight(body) { case ((n, k), b) =>
        k.fold[Value](Value.TypeLambda(n, b))(Value.KindLambda(n, _, b))
      }
    }
    private val primitives = Map("+" -> "add", "-" -> "sub", "*" -> "mul", "/" -> "div", "%" -> "mod",
      "==" -> "eq", "!=" -> "ne", "<" -> "lt", "<=" -> "le", ">" -> "gt", ">=" -> "ge", "&&" -> "and", "||" -> "or")
    private def value(minimum: Int = 2): Value = {
      var result = unary()
      while primitives.contains(current) && precedence(current) >= minimum do {
        val op = take()
        val right = expression(Term.Return(value(precedence(op) + 1)))
        result = if op == "&&" || op == "||" then right match {
          case Term.Return(v) if Surface.total(v) => Value.Primitive(primitives(op), List(result, v))
          case _ => atomize(if op == "&&" then Term.If(result, right, Term.Return(Value.Boolean(false)))
            else Term.If(result, Term.Return(Value.Boolean(true)), right))
        } else Value.Primitive(primitives(op), List(result, atomize(right)))
      }
      result
    }
    private def unary(): Value =
      if accept("!") then Value.Primitive("not", List(unary()))
      else if accept("-") then {
        if current.matches("[0-9]+") then Value.Integer(-BigInt(take()))
        else Value.Primitive("sub", List(Value.Integer(0), unary()))
      } else postfix()

    private def postfix(): Value = {
      var result = atom()
      var more = true
      while more do {
        if accept(".") then result = atomize(operationCall(result))
        else if accept("match") then result = atomize(Term.Match(result, matchCases()))
        else if current == "(" && !newlineBeforeCurrent then {
          val arg = argument()
          result = atomize(Term.Apply(result, arg))
        }
        else if current == "{" && !newlineBeforeCurrent then {
          take(); val capability = atomize(term()); expect("}"); result = Value.Instantiate(result, capability)
        } else if current == "[" then {
          take()
          taggedApplication(result) match {
            case Some(application) => result = application
            case None =>
              val arguments = scala.collection.mutable.ListBuffer(polymorphicArgument())
              while accept(",") do arguments += polymorphicArgument()
              expect("]")
              result = arguments.foldLeft(result)(Value.InstantiatePolymorphic.apply)
          }
        } else more = false
      }
      result
    }
    private def argument(): Value = {
      expect("(")
      val args = scala.collection.mutable.ListBuffer.empty[Value]
      if current != ")" then { args += atomize(term()); while accept(",") do args += atomize(term()) }
      expect(")"); Surface.arguments(args.toList)
    }
    private def term(): Term = {
      val result = expression(rawTerm())
      if accept(":") then Term.Ascribe(result, computation()) else result
    }
    private def rawTerm(): Term = current match {
      case "match" =>
        take(); val scrutinee = argument(); Term.Match(scrutinee, matchCases())
      case "{" => block()
      case "return" => take(); term()
      case "assert" => take(); expect("("); val condition = atomize(term()); expect(")"); Term.Assert(condition)
      case "if" =>
        take(); expect("("); val condition = atomize(term()); expect(")")
        val whenTrue = term(); expect("else"); Term.If(condition, whenTrue, term())
      case "try" =>
        take(); val body = term(); expect("with"); val cap = identifier()
        var interface: Option[InterfaceDeclaration] = None
        var interfaceArguments = List.empty[Type]
        var legacyName: Option[String] = None
        val result = if accept(":") then {
          if interfaces.contains(current) then {
            val declaration = interfaces(take())
            interfaceArguments = typeArguments()
            if interfaceArguments.size != declaration.typeParameters.size then fail(s"Wrong number of type arguments for interface ${declaration.name}")
            val actuals = declaration.typeParameters.zip(interfaceArguments).toMap
            interface = Some(declaration.copy(operations = declaration.operations.map(o =>
              o.copy(argument = Surface.typeArguments(o.argument, actuals), result = Surface.typeArguments(o.result, actuals)))))
            None
          }
          else {
            val annotation = computation()
            annotation.value match { case Type.Token(n) => legacyName = Some(n); case _ => () }
            Some(annotation) // Archived with h: Computation syntax, including token types.
          }
        } else None
        expect("{")
        val ops = scala.collection.mutable.ListBuffer.empty[Operation]
        while accept("def") do {
          val name = operationName()
          val declared = interface.map(i => i.operations.find(_.name == name).getOrElse(
            fail(s"Operation $name is not declared in interface ${i.name}")))
          val params = parameters(optionalParens = true)
          if declared.exists(d => d.arity != params.size) then fail(s"Wrong number of parameters for operation $name")
          val parameter = params.binder
          val arg = declared.map(_.argument).getOrElse(params.argument)
          val resultAnnotated = accept(":")
          val (res, returned) = if resultAnnotated then {
            val (t, n) = operationResult(); (params.result(t), n)
          } else declared.map(d => (d.result, d.returned)).getOrElse(
            fail(legacyName.fold(s"Operation $name needs a result annotation or a named interface")(n => s"Unknown interface $n")))
          val answers = if accept("/") then {
            val (a, b) = answerTransition(); Some((params.result(a), params.result(b)))
          } else None
          // Match Effekt's implicit resumption name. Keep explicit names for
          // the unchanged archived regression fixtures.
          val k = if accept("resume") then identifier() else "resume"
          expect("=")
          ops += Operation(name, arg, res, answers, parameter, k, params.bind(term()), returned = returned,
            interfaceName = interface.map(_.name), argumentAnnotated = declared.isEmpty,
            resultAnnotated = resultAnnotated, interfaceArguments = interfaceArguments, argumentAnnotations = if declared.nonEmpty then params.fields.map(_._2) else Nil)
          accept(";")
        }
        interface.foreach { i =>
          val missing = i.operations.map(_.name).toSet -- ops.map(_.name)
          if missing.nonEmpty then fail(s"Missing operations in interface ${i.name}: ${missing.toList.sorted.mkString(", ")}")
        }
        val (returned, onReturn) = if accept("return") then {
          val explicit = accept("effect")
          val name = identifier(); expect("=>"); val body = term()
          (name, if explicit then Term.ExplicitReturn(body) else body)
        } else {
          val name = s"returned#${tokens(position).offset}"
          (name, Term.Return(Value.Variable(name)))
        }
        accept(";"); expect("}")
        result.fold[Term](Term.InferredHandle(cap, ops.toList, body, returned, onReturn))(
          Term.Handle(cap, _, ops.toList, body, returned, onReturn))
      case _ => Term.Return(value())
    }
    private def operationCall(receiver: Value): Term = {
      val name = receiver match {
        case Value.Variable(name) => name
        case Value.Capability(name) => name
        case _ => fail("An operation receiver must be a capability name or alias")
      }
      val op = operationName()
      if accept("[") then {
        var ev: Option[Proof] = None; var cc: Option[AnswerContext] = None
        var singleName: Option[String] = None
        val legacyTagged = Set("evidence", "atc")(current) &&
          !tokens.lift(position + 1).exists(t => Set(";", "]", "+")(t.text))
        if legacyTagged then {
          // Keep tagged operation annotations for archived sources.
          def entry(): Unit = {
            if accept("evidence") then { if ev.nonEmpty then fail("Duplicate evidence annotation"); ev = Some(proof()) }
            else if accept("atc") then { if cc.nonEmpty then fail("Duplicate ATC annotation"); cc = Some(context()) }
            else fail("An operation annotation starts with evidence or atc")
          }
          entry(); while accept(";") do entry()
        } else {
          val start = position
          // Distinguish constructor syntax here; leave a lone name to kind checking.
          val candidate = try {
            val p = proof()
            if current == ";" || current == "]" then Some(p) else None
          } catch { case _: Diagnostic => None }
          candidate match {
            case Some(p) if accept(";") =>
              ev = Some(p)
              if current != "]" then cc = Some(context())
            case Some(Proof.Variable(n)) => singleName = Some(n)
            case Some(p) => ev = Some(p)
            case None =>
              position = start
              accept(";") // Retain the previously accepted empty evidence position.
              if current != "]" then cc = Some(context())
          }
          if ev.isEmpty && cc.isEmpty && singleName.isEmpty then
            fail("An operation annotation needs evidence or an answer type context")
        }
        expect("]")
        val arg = argument()
        singleName match {
          case Some(n) => Term.SingleAnnotatedPerform(name, op, arg, n)
          case None => Term.AnnotatedPerform(name, op, arg, ev, cc)
        }
      } else Term.Perform(name, op, argument())
    }
    private def matchCases(): List[MatchCase] = {
      expect("{")
      val cases = scala.collection.mutable.ListBuffer.empty[MatchCase]
      while accept("case") do {
        val constructor = identifier()
        val parameters = scala.collection.mutable.ListBuffer.empty[String]
        if accept("(") then {
          def parameter(): String = if accept("_") then fresh("ignored") else identifier()
          if current != ")" then { parameters += parameter(); while accept(",") do parameters += parameter() }
          expect(")")
        }
        expect("=>")
        cases += MatchCase(constructor, parameters.toList, statements(Set("case", "}")))
        accept(";")
      }
      expect("}"); cases.toList
    }
    private def statements(stops: Set[String] = Set("}")): Term = {
        if stops(current) then Term.Return(Value.Unit)
        else if Set("region", "distance", "evidence", "atc", "cty")(current) then {
          val tag = take(); val name = identifier(); expect("="); val a = annotation(tag); expect(";")
          Term.AnnotationAlias(name, a, statements(stops))
        } else if accept("type") then {
          val name = identifier(); expect("="); val definition = tpe(); separator()
          Term.TypeAlias(name, definition, statements(stops))
        } else if accept("val") then {
          val name = identifier()
          val annotation = if accept(":") then Some(computation()) else None
          expect("="); val binding = term(); separator()
          Term.Let(name, annotation.fold(binding)(Term.Ascribe(binding, _)), statements(stops))
        } else if accept("def") then {
          val d = definition()
          d.wrap(statements(stops))
        } else {
          val result = term()
          val separated = accept(";") || newlineBeforeCurrent
          if separated && !stops(current) then {
            val discard = s"discard#${tokens(position).offset}"
            Term.Let(discard, result, statements(stops))
          } else result
        }
    }
    private def block(): Term = {
      expect("{"); val result = statements(); expect("}"); result
    }
  }
}

package effekt.atm

import Syntax.*

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
          while i < input.length && (input(i).isLetterOrDigit || input(i) == '_') do i += 1
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
      "data", "measure", "match", "case", "type", "pred", "typefun", "predfun", "assert", "rec", "then", "fst", "snd")
    private def identifier(): String = {
      if !current.matches("[a-zA-Z_][a-zA-Z0-9_]*") || keywords(current) then
        fail(s"Expected an identifier, found '$current'")
      take()
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
        if current != "}" then expect(";")
      }
      expect("}"); accept(";")
      DataDeclaration(name, constructors.toList)
    }
    def program(): Program = {
      val declarations = scala.collection.mutable.ListBuffer.empty[DataDeclaration]
      while accept("data") do declarations += dataDeclaration()
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
      expect("def"); expect("main"); expect("("); expect(")"); expect(":")
      val result = computation()
      expect("=")
      val body = block()
      if current != "<end>" then fail("Unexpected input after program")
      Program(result, body, declarations.toList, measures.toList)
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
    private def localRegion(): Option[String] =
      if accept("[") then { val name = identifier(); expect("]"); Some(name) } else None
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
          val first = tpe()
          val argument = if accept(",") then Type.Pair(first, tpe()) else first
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
      expect("("); val parameter = identifier(); val argument = if accept(":") then Some(tpe()) else None
      expect(")")
      val result = if accept(":") then Some(computation()) else None
      val body = block()
      if local then Value.LocalLambda(parameter, argument, result, body, region) else (argument, result) match {
        case (Some(a), Some(r)) => Value.Lambda(parameter, a, r, body)
        case (Some(a), None) => Value.InferredLambda(parameter, a, body)
        case (None, r) => Value.UnannotatedLambda(parameter, r, body)
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
          val v = value()
          val result = if accept(",") then Value.Primitive("pair", List(v, value())) else v
          expect(")"); result
        }
      case "cap" => take(); expect("("); val name = identifier(); expect(")"); Value.Capability(name)
      case "token" => take(); expect("("); val name = identifier(); expect(")"); Value.Token(name)
      case "fun" => take(); function()
      case "capfun" =>
        take(); val scoped = accept("here"); expect("("); val name = identifier(); expect(":")
        val ops = signatures(); expect(")"); expect("{"); val body = value(); expect("}")
        Value.CapabilityLambda(name, ops, body, scoped)
      case "typefun" =>
        take(); expect("["); val name = identifier(); expect("]"); expect("{")
        val body = value(); expect("}"); Value.TypeLambda(name, body)
      case "predfun" =>
        take(); expect("["); val name = identifier(); val args = predicateSorts(); expect("]"); expect("{")
        val body = value(); expect("}"); Value.PredicateLambda(name, args, body)
      case "effectfun" =>
        take(); expect("["); val name = identifier(); val constraint = optionalBound(); expect("]")
        expect("{"); val body = value(); expect("}"); Value.EffectLambda(name, body, constraint)
      case name @ ("zero" | "add" | "not" | "fst" | "snd") =>
        take(); expect("(")
        val args = scala.collection.mutable.ListBuffer.empty[Value]
        if current != ")" then {
          args += value()
          while accept(",") do args += value()
        }
        expect(")"); Value.Primitive(name, args.toList)
      case name if constructorNames(name) =>
        take(); expect("(")
        val args = scala.collection.mutable.ListBuffer.empty[Value]
        if current != ")" then { args += value(); while accept(",") do args += value() }
        expect(")"); Value.Constructor(name, args.toList)
      case _ => Value.Variable(identifier())
    }
    private val primitives = Map("+" -> "add", "-" -> "sub", "*" -> "mul", "/" -> "div", "%" -> "mod",
      "==" -> "eq", "!=" -> "ne", "<" -> "lt", "<=" -> "le", ">" -> "gt", ">=" -> "ge", "&&" -> "and", "||" -> "or")
    private def value(minimum: Int = 2): Value = {
      var result = unary()
      while primitives.contains(current) && precedence(current) >= minimum do {
        val op = take()
        result = Value.Primitive(primitives(op), List(result, value(precedence(op) + 1)))
      }
      result
    }
    private def unary(): Value =
      if accept("!") then Value.Primitive("not", List(unary())) else postfix()

    private def postfix(): Value = {
      var result = atom()
      var more = true
      while more do {
        if accept("{") then {
          val capability = value(); expect("}"); result = Value.Instantiate(result, capability)
        } else if accept("[") then {
          if accept("effect") then {
            val argument = effect(); expect("]"); result = Value.InstantiateEffect(result, argument)
          } else if accept("type") then {
            val argument = tpe(); expect("]"); result = Value.InstantiateType(result, argument)
          } else {
            expect("pred"); expect("(")
            val params = scala.collection.mutable.ListBuffer.empty[(String, Logic.Sort)]
            def param(): Unit = { val name = identifier(); expect(":"); params += ((name, sort())) }
            if current != ")" then { param(); while accept(",") do param() }
            expect(")"); expect("=>"); val body = predicate(); expect("]")
            result = Value.InstantiatePredicate(result, params.toList, body)
          }
        } else more = false
      }
      result
    }
    private def argument(): Value = {
      expect("(")
      val result = if current == ")" then Value.Unit else value()
      expect(")"); result
    }
    private def term(): Term = {
      val result = rawTerm()
      if accept(":") then Term.Ascribe(result, computation()) else result
    }
    private def rawTerm(): Term = current match {
      case "match" =>
        take(); val scrutinee = argument(); expect("{")
        val cases = scala.collection.mutable.ListBuffer.empty[MatchCase]
        while accept("case") do {
          val constructor = identifier(); expect("(")
          val parameters = scala.collection.mutable.ListBuffer.empty[String]
          if current != ")" then { parameters += identifier(); while accept(",") do parameters += identifier() }
          expect(")"); expect("=>")
          cases += MatchCase(constructor, parameters.toList, block()); accept(";")
        }
        expect("}"); Term.Match(scrutinee, cases.toList)
      case "{" => block()
      case "return" => take(); Term.Return(value())
      case "assert" => take(); expect("("); val condition = value(); expect(")"); Term.Assert(condition)
      case "if" =>
        take(); expect("("); val condition = value(); expect(")")
        val whenTrue = block(); expect("else"); Term.If(condition, whenTrue, block())
      case "try" =>
        take(); val body = block(); expect("with"); val cap = identifier()
        val result = if accept(":") then Some(computation()) else None
        expect("{")
        val ops = scala.collection.mutable.ListBuffer.empty[Operation]
        while accept("def") do {
          val name = identifier(); expect("("); val parameter = identifier(); expect(":")
          val arg = tpe(); expect(")"); expect(":"); val (res, returned) = operationResult(); expect("/")
          val (initial, finalAnswer) = answerTransition()
          // Match Effekt's implicit resumption name. Keep explicit names for
          // the unchanged archived regression fixtures.
          val k = if accept("resume") then identifier() else "resume"
          expect("=")
          ops += Operation(name, arg, res, initial, finalAnswer, parameter, k, block(), returned = returned)
          accept(";")
        }
        expect("return"); val explicit = accept("effect")
        val returned = identifier(); expect("=>"); val returnedBody = block()
        val onReturn = if explicit then Term.ExplicitReturn(returnedBody) else returnedBody
        accept(";"); expect("}")
        result.fold[Term](Term.InferredHandle(cap, ops.toList, body, returned, onReturn))(
          Term.Handle(cap, _, ops.toList, body, returned, onReturn))
      case _ =>
        val receiver = value()
        if accept(".") then {
          val name = receiver match {
            case Value.Variable(name) => name
            case Value.Capability(name) => name
            case _ => fail("An operation receiver must be a capability name or alias")
          }
          val op = identifier(); Term.Perform(name, op, argument())
        } else if current == "(" then Term.Apply(receiver, argument())
        else Term.Return(receiver)
    }
    private def block(): Term = {
      expect("{")
      def statements(): Term = {
        if current == "}" then Term.Return(Value.Unit)
        else if accept("type") then {
          val name = identifier(); expect("="); val definition = tpe(); expect(";")
          Term.TypeAlias(name, definition, statements())
        } else if accept("val") then {
          val name = identifier()
          val annotation = if accept(":") then Some(computation()) else None
          expect("="); val binding = term(); expect(";")
          Term.Let(name, annotation.fold(binding)(Term.Ascribe(binding, _)), statements())
        } else if accept("def") then {
          val recursive = accept("rec")
          val local = accept("local"); val region = if local then localRegion() else None
          if recursive && region.nonEmpty then fail("A recursive local definition uses its enclosing lexical region")
          val name = identifier()
          expect("("); val param = identifier(); expect(":"); val arg = tpe(); expect(")")
          val res = if accept(":") then Some(computation()) else None
          expect("="); val body = block(); accept(";")
          if recursive then Term.LetRec(name, param, arg, res.getOrElse(fail("A recursive definition requires a result annotation")), body, statements(), local)
          else if local then Term.Let(name, Term.Return(Value.LocalLambda(param, Some(arg), res, body, region)), statements())
          else Term.Let(name, Term.Return(res.fold[Value](Value.InferredLambda(param, arg, body))(Value.Lambda(param, arg, _, body))), statements())
        } else {
          val result = term()
          val separated = accept(";")
          if separated && current != "}" then {
            val discard = s"discard#${tokens(position).offset}"
            Term.Let(discard, result, statements())
          } else result
        }
      }
      val result = statements(); expect("}"); result
    }
  }
}

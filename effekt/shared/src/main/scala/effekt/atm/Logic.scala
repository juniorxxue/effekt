package effekt.atm

/** Sorted, first-order verification conditions. Source text is never sent to a
 * solver: only this validated tree is serialized to SMT-LIB.
 */
object Logic {
  enum Sort {
    case Int, Bool, Unit
    case Pair(first: Sort, second: Sort)
    case Data(name: String)
  }
  enum Expr {
    case Integer(value: BigInt)
    case Boolean(value: scala.Boolean)
    case Unit
    case Variable(name: String)
    case App(operator: String, arguments: List[Expr])
    case Predicate(name: String, arguments: List[Expr])
    case Exists(name: String, sort: Sort, body: Expr)
    case Forall(name: String, sort: Sort, body: Expr)
  }
  import Expr.*
  case class MeasureCase(constructor: String, parameters: List[String], body: Expr)
  case class Measure(name: String, parameters: List[(String, Sort)], result: Sort, cases: List[MeasureCase])
  val True: Expr = Boolean(true)
  val False: Expr = Boolean(false)
  def and(a: Expr, b: Expr): Expr = (a, b) match {
    case (Boolean(true), x) => x
    case (x, Boolean(true)) => x
    case (Boolean(false), _) | (_, Boolean(false)) => False
    case _ if a == b => a
    case _ => App("&&", List(a, b))
  }
  def not(a: Expr): Expr = a match {
    case Boolean(b) => Boolean(!b)
    case App("!", List(b)) => b
    case _ => App("!", List(a))
  }
  def eq(a: Expr, b: Expr): Expr = if a == b then True else App("==", List(a, b))
  def all(xs: Iterable[Expr]): Expr = xs.foldLeft(True)(and)

  def variables(e: Expr): Set[String] = e match {
    case Variable(name) => Set(name)
    case App(_, xs) => xs.flatMap(variables).toSet
    case Predicate(_, xs) => xs.flatMap(variables).toSet
    case Exists(name, _, body) => variables(body) - name
    case Forall(name, _, body) => variables(body) - name
    case _ => Set.empty
  }
  def predicates(e: Expr): Set[String] = e match {
    case Predicate(name, xs) => xs.flatMap(predicates).toSet + name
    case App(_, xs) => xs.flatMap(predicates).toSet
    case Exists(_, _, body) => predicates(body)
    case Forall(_, _, body) => predicates(body)
    case _ => Set.empty
  }
  // All binders in checked trees have fresh identities. The source resolver
  // creates those identities before these substitutions can be used.
  def substitute(e: Expr, name: String, replacement: Expr): Expr = e match {
    case Variable(n) if n == name => replacement
    case App(op, xs) => App(op, xs.map(substitute(_, name, replacement)))
    case Predicate(p, xs) => Predicate(p, xs.map(substitute(_, name, replacement)))
    case Exists(n, s, body) if n != name => Exists(n, s, substitute(body, name, replacement))
    case Forall(n, s, body) if n != name => Forall(n, s, substitute(body, name, replacement))
    case other => other
  }
  def instantiate(e: Expr, name: String, parameters: List[String], body: Expr): Expr = e match {
    case Predicate(p, xs) if p == name =>
      // Parameters are fresh, so simultaneous substitution cannot capture.
      parameters.zip(xs).foldLeft(body) { case (b, (n, x)) => substitute(b, n, x) }
    case Predicate(p, xs) => Predicate(p, xs.map(instantiate(_, name, parameters, body)))
    case App(op, xs) => App(op, xs.map(instantiate(_, name, parameters, body)))
    case Exists(n, s, b) => Exists(n, s, instantiate(b, name, parameters, body))
    case Forall(n, s, b) => Forall(n, s, instantiate(b, name, parameters, body))
    case other => other
  }

  case class Context(variables: Map[String, Sort] = Map.empty,
                     predicates: Map[String, List[Sort]] = Map.empty,
                     assumptions: List[Expr] = Nil,
                     datatypes: Map[String, List[(String, List[Sort])]] = Map.empty,
                     measures: Map[String, Measure] = Map.empty) {
    def bind(name: String, sort: Sort): Context = {
      validateSort(sort, this)
      copy(variables = variables.updated(name, sort))
    }
    def assume(p: Expr): Context = copy(assumptions = p :: assumptions)
  }
  def validateSort(s: Sort, context: Context): Unit = s match {
    case Sort.Data(n) if !context.datatypes.contains(n) => throw Syntax.Diagnostic("Unknown logical datatype " + n)
    case Sort.Pair(a, b) => validateSort(a, context); validateSort(b, context)
    case _ => ()
  }
  def sort(e: Expr, context: Context): Sort = {
    def fail(message: String): Nothing = throw Syntax.Diagnostic(message)
    def expect(e: Expr, wanted: Sort): Unit =
      if sort(e, context) != wanted then fail(s"Refinement expression ${show(e)} must have sort $wanted")
    e match {
      case Integer(_) => Sort.Int
      case Boolean(_) => Sort.Bool
      case Unit => Sort.Unit
      case Variable(n) => context.variables.getOrElse(n, fail(s"Unknown refinement variable ${display(n)}"))
      case Predicate(n, xs) =>
        val params = context.predicates.getOrElse(n, fail(s"Unknown refinement predicate ${display(n)}"))
        if params.length != xs.length then fail(s"Wrong number of arguments to predicate ${display(n)}")
        xs.zip(params).foreach((x, s) => expect(x, s)); Sort.Bool
      case Exists(n, s, b) =>
        if sort(b, context.bind(n, s)) != Sort.Bool then fail("An existential body must be Bool")
        Sort.Bool
      case Forall(n, s, b) =>
        if sort(b, context.bind(n, s)) != Sort.Bool then fail("A universal body must be Bool")
        Sort.Bool
      case App(op, xs) =>
        def signature(inputs: List[Sort], output: Sort): Sort = {
          if inputs.length != xs.length then fail(s"Wrong number of operands for $op")
          xs.zip(inputs).foreach((x, s) => expect(x, s)); output
        }
        op match {
          case m if m.startsWith("measure:") =>
            val definition = context.measures.getOrElse(m.stripPrefix("measure:"), fail("Unknown logical measure " + m))
            signature(definition.parameters.map(_._2), definition.result)
          case c if c.startsWith("constructor:") =>
            val n = c.stripPrefix("constructor:")
            val matches = context.datatypes.toList.flatMap((owner, cs) => cs.collect { case (`n`, fields) => (owner, fields) })
            if matches.size != 1 then fail(s"Unknown or ambiguous logical constructor $n")
            val (owner, fields) = matches.head
            signature(fields, Sort.Data(owner))
          case "pair" =>
            if xs.size != 2 then fail("A pair needs two fields")
            Sort.Pair(sort(xs.head, context), sort(xs(1), context))
          case "fst" | "snd" =>
            if xs.size != 1 then fail("A projection needs one pair")
            sort(xs.head, context) match {
              case Sort.Pair(a, b) => if op == "fst" then a else b
              case _ => fail("A projection expects a pair")
            }
          case "+" | "-" | "*" | "/" | "%" => signature(List(Sort.Int, Sort.Int), Sort.Int)
          case "neg" => signature(List(Sort.Int), Sort.Int)
          case "<" | "<=" | ">" | ">=" => signature(List(Sort.Int, Sort.Int), Sort.Bool)
          case "&&" | "||" | "=>" => signature(List(Sort.Bool, Sort.Bool), Sort.Bool)
          case "!" => signature(List(Sort.Bool), Sort.Bool)
          case "==" | "!=" =>
            if xs.length != 2 || sort(xs.head, context) != sort(xs(1), context) then fail("Equality operands need the same base sort")
            Sort.Bool
          case "ite" =>
            if xs.length != 3 then fail("A logical conditional needs three operands")
            expect(xs.head, Sort.Bool)
            val s = sort(xs(1), context); expect(xs(2), s); s
          case _ => fail(s"Unknown logical operator $op")
        }
    }
  }
  private def display(name: String): String = name.takeWhile(_ != '#')
  def show(e: Expr): String = e match {
    case Integer(n) => n.toString
    case Boolean(b) => b.toString
    case Unit => "()"
    case Variable(n) => display(n)
    case Predicate(n, args) => args.map(show).mkString(s"${display(n)}(", ", ", ")")
    case Exists(n, s, b) => s"exists ${display(n)}: $s. ${show(b)}"
    case Forall(n, s, b) => s"forall ${display(n)}: $s. ${show(b)}"
    case App("ite", List(c, a, b)) => s"if ${show(c)} then ${show(a)} else ${show(b)}"
    case App(op, xs) if op.startsWith("constructor:") => xs.map(show).mkString(op.stripPrefix("constructor:") + "(", ", ", ")")
    case App(op, xs) if op.startsWith("measure:") => xs.map(show).mkString(op.stripPrefix("measure:") + "(", ", ", ")")
    case App(op, List(a)) => s"$op(${show(a)})"
    case App(op, List(a, b)) => s"(${show(a)} $op ${show(b)})"
    case App(op, xs) => xs.map(show).mkString(s"$op(", ", ", ")")
  }

  enum Verdict {
    case Valid, Invalid
    case Unknown(reason: String)
  }
  trait Solver { def prove(context: Context, conclusion: Expr): Verdict }

  def query(context: Context, conclusion: Expr): String = {
    val goal = all(context.assumptions :+ not(conclusion))
    if sort(goal, context) != Sort.Bool then throw Syntax.Diagnostic("Verification condition must have sort Bool")
    def name(n: String): String = "|" + n + "|"
    def sortName(s: Sort): String = s match {
      case Sort.Data(n) => s"Data_${n.length}_$n"
      case Sort.Pair(a, b) => s"Pair_${sortName(a).length}_${sortName(a)}_${sortName(b).length}_${sortName(b)}"
      case other => other.toString
    }
    def emit(e: Expr, scope: Context = context): String = {
      def child(e: Expr): String = emit(e, scope)
      e match {
      case Integer(n) if n < 0 => s"(- ${-n})"
      case Integer(n) => n.toString
      case Boolean(b) => b.toString
      case Unit => "unit"
      case Variable(n) => name(n)
      case Predicate(n, Nil) => name(n)
      case Predicate(n, xs) => xs.map(child).mkString(s"(${name(n)} ", " ", ")")
      case Exists(n, s, b) => s"(exists ((${name(n)} ${sortName(s)})) ${emit(b, scope.bind(n, s))})"
      case Forall(n, s, b) => s"(forall ((${name(n)} ${sortName(s)})) ${emit(b, scope.bind(n, s))})"
      case app @ App("pair", xs) => xs.map(child).mkString(s"(mk_${sortName(sort(app, scope))} ", " ", ")")
      case App(op @ ("fst" | "snd"), List(x)) => s"(${op}_${sortName(sort(x, scope))} ${child(x)})"
      case App(op @ ("/" | "%"), List(a, b)) =>
        val x = child(a); val y = child(b)
        val absX = s"(ite (< $x 0) (- $x) $x)"
        val absY = s"(ite (< $y 0) (- $y) $y)"
        val quotient = s"(ite (= (< $x 0) (< $y 0)) (div $absX $absY) (- (div $absX $absY)))"
        if op == "/" then quotient else s"(- $x (* $y $quotient))"
      case App(op, xs) if op.startsWith("constructor:") =>
        val ctor = "Ctor_" + op.stripPrefix("constructor:")
        if xs.isEmpty then ctor else xs.map(child).mkString(s"($ctor ", " ", ")")
      case App(op, xs) if op.startsWith("measure:") => xs.map(child).mkString("(Measure_" + op.stripPrefix("measure:") + " ", " ", ")")
      case App(op, xs) =>
        val smt = Map("==" -> "=", "!=" -> "distinct", "&&" -> "and", "||" -> "or", "!" -> "not", "neg" -> "-").getOrElse(op, op)
        xs.map(child).mkString(s"($smt ", " ", ")")
    }
    }
    def components(s: Sort): List[Sort] = s match {
      case p @ Sort.Pair(a, b) => components(a) ++ components(b) :+ p
      case Sort.Unit => List(Sort.Unit)
      case d: Sort.Data => List(d)
      case _ => Nil
    }
    def expressionSorts(e: Expr, scope: Context): List[Sort] = e match {
      case Exists(n, s, b) => components(s) ++ expressionSorts(b, scope.bind(n, s))
      case Forall(n, s, b) => components(s) ++ expressionSorts(b, scope.bind(n, s))
      case App(_, xs) => xs.flatMap(expressionSorts(_, scope)) ++ components(sort(e, scope))
      case Predicate(_, xs) => xs.flatMap(expressionSorts(_, scope))
      case _ => components(sort(e, scope))
    }
    val measureSorts = context.measures.values.toList.flatMap { m =>
      val owner = m.parameters.head._2.asInstanceOf[Sort.Data].name
      m.parameters.flatMap(p => components(p._2)) ++ components(m.result) ++ m.cases.flatMap { c =>
        val fields = context.datatypes(owner).find(_._1 == c.constructor).get._2
        val scope = (m.parameters ++ c.parameters.zip(fields)).foldLeft(context) { case (e, (n, s)) => e.bind(n, s) }
        expressionSorts(c.body, scope)
      }
    }
    val sorts = ((context.variables.values.toList ++ context.predicates.values.flatten ++
      context.datatypes.values.flatten.flatMap(_._2)).flatMap(components) ++ measureSorts ++
      context.datatypes.keys.map(Sort.Data.apply) ++ expressionSorts(goal, context)).distinct.sortBy(sortName)
    // Declare all data and pair schemas together, so mutually recursive data
    // (including recursion through pairs) have well-scoped field sorts.
    val schemas = if sorts.isEmpty then Nil else {
      val headers = sorts.map(s => s"(${sortName(s)} 0)").mkString(" ")
      val bodies = sorts.map {
        case Sort.Unit => "((unit))"
        case p @ Sort.Pair(a, b) =>
          val n = sortName(p)
          s"((mk_$n (fst_$n ${sortName(a)}) (snd_$n ${sortName(b)})))"
        case Sort.Data(n) => context.datatypes(n).map { (c, fs) =>
          val fields = fs.zipWithIndex.map((s, i) => s"(Field_${c}_$i ${sortName(s)})")
          (s"Ctor_$c" :: fields).mkString("(", " ", ")")
        }.mkString("(", " ", ")")
        case _ => throw Syntax.Diagnostic("Unexpected primitive datatype declaration")
      }.mkString(" ")
      List(s"(declare-datatypes ($headers) ($bodies))")
    }
    val vars = variables(goal).toList.sorted.map(n => s"(declare-const ${name(n)} ${sortName(context.variables(n))})")
    val preds = predicates(goal).toList.sorted.map(n => s"(declare-fun ${name(n)} (${context.predicates(n).map(sortName).mkString(" ")}) Bool)")
    val measures = context.measures.values.toList.sortBy(_.name).map { m =>
      val params = m.parameters.map((n, s) => s"(${name(n)} ${sortName(s)})").mkString(" ")
      val owner = m.parameters.head._2.asInstanceOf[Sort.Data].name
      val clauses = m.cases.map { c =>
        val fields = context.datatypes(owner).find(_._1 == c.constructor).get._2
        val scope = c.parameters.zip(fields).foldLeft(m.parameters.foldLeft(context)((e, p) => e.bind(p._1, p._2)))((e, p) => e.bind(p._1, p._2))
        val pattern = if c.parameters.isEmpty then "Ctor_" + c.constructor
          else c.parameters.map(name).mkString("(Ctor_" + c.constructor + " ", " ", ")")
        s"($pattern ${emit(c.body, scope)})"
      }.mkString(" ")
      (s"(Measure_${m.name} ($params) ${sortName(m.result)})", s"(match ${name(m.parameters.head._1)} ($clauses))")
    }
    val functions = if measures.isEmpty then Nil else List(
      s"(define-funs-rec (${measures.map(_._1).mkString(" ")}) (${measures.map(_._2).mkString(" ")}))")
    (List("(set-option :timeout 3000)", "(set-logic ALL)") ++ schemas ++ functions ++ vars ++ preds ++
      List(s"(assert ${emit(goal)})", "(check-sat)")).mkString("\n")
  }
}

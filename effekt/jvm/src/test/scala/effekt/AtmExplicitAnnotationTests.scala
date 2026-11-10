package effekt

import effekt.atm.{Annotations, Checker, Core, DirectBackend, Evidence, Parser, Syntax, TypeEquality}
import effekt.atm.Syntax.*
import java.nio.file.{Files, Path}

class AtmExplicitAnnotationTests extends munit.FunSuite {
  private def checked(source: String): Checker.CheckedProgram =
    Parser.parse(source).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def rejected(source: String, reason: String): Unit = {
    val parsed = Parser.parse(source).fold(e => fail(e.message), identity)
    Checker.check(parsed) match {
      case Left(e) => assert(e.message.contains(reason), e.message)
      case Right(_) => fail("Accepted an invalid annotation")
    }
  }
  private def run(source: String, expected: String): Unit = {
    val p = checked(source)
    Core.elaborate(p)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
  }
  private val callback = """val callback = typefun [R: Reg, D: Dist, E: Ev(R, D, outer), K: ATC(D)] {
    fun local[R](u: Unit): Int / (K[Int] => K[Bool]) {
      outer.ask[E; K]()
    }
  };"""
  private def layers(n: Int, hole: String): String =
    (0 until n).foldLeft(hole)((rest, _) => s"Int / (Int => $rest)")
  private def source(depth: Int): String = {
    val evidence = if depth == 0 then "refl" else (1 to depth).reverse.map(i => s"step(h$i)").mkString(" + ")
    val call = s"callback[region here][distance $depth][evidence $evidence][atc ${layers(depth, "[]")}]()"
    val body = (1 to depth).reverse.foldLeft(call) { (body, i) =>
      val answer = layers(i, "Bool")
      s"""try { $body } with h$i: $answer {
        def unused(u: Unit): Unit / ($answer => $answer) = { resume() }
        return x => { x }
      }"""
    }
    s"""def main(): Bool = {
      try { $callback $body } with outer: Bool {
        def ask(u: Unit): Int / (Int => Bool) = { val n = resume(42); n == 42 }
        return x => { x }
      }
    }"""
  }

  for depth <- 0 to 2 do test(s"symbolic ATC and evidence parameters execute at depth $depth") {
    run(source(depth), "true")
  }
  test("the documented annotated repair executes") {
    run(Files.readString(Path.of("examples/atm/annotations/polymorphic-outer-call.effekt")), "true")
  }
  test("explicit relative evidence preserves the two-capability helper abstraction") {
    val s = Files.readString(Path.of("examples/atm/annotations/multiple-capabilities.effekt"))
    val old = Parser.parse(Files.readString(Path.of("examples/atm/multiple-capabilities.effekt"))).toOption.get
    run(s, AtmReference.run(old))
    rejected(s.replace("[step(inner)]", "[refl]"), "Annotation kind mismatch")
  }
  test("the unannotated symbolic-distance case remains an honest inference failure") {
    rejected(Files.readString(Path.of("examples/atm/infer/polymorphic-outer-call.effekt")), "ATC inference requires a concrete")
  }
  test("explicit annotations work with lexical aliases and equivalent distance sums") {
    val s = source(1).replace("callback[region here]", """region R = here; distance D = 0 + 1;
      evidence E = step(h1); atc K = Int / (Int => []);
      callback[region R]""")
      .replace("[distance 1][evidence step(h1)][atc Int / (Int => [])]", "[distance D][evidence E][atc K]")
    run(s, "true")
  }
  test("abstract contexts and evidence compose with additive depths") {
    val nested = """val callback = typefun [R: Reg, S: Reg, D: Dist, N: Dist,
      E: Ev(R, D, S), F: Ev(S, N, outer), K: ATC(D), J: ATC(N)] {
      fun local[R](u: Unit): Int / (K[J[Int]] => K[J[Bool]]) {
        outer.ask[E + F; K[J]]()
      }
    };"""
    val oldCall = "callback[region here][distance 2][evidence step(h2) + step(h1)][atc Int / (Int => Int / (Int => []))]()"
    val newCall = """callback[region here][region h1][distance 1][distance 1]
      [evidence step(h2)][evidence step(h1)][atc Int / (Int => [])][atc Int / (Int => [])]()"""
    run(source(2).replace(callback, nested).replace(oldCall, newCall), "true")
  }
  test("declared kind telescopes alpha-rename dependencies during subsumption") {
    val annotation = """val callback: forall S: Reg. (forall N: Dist.
      (forall F: Ev(S, N, outer). (forall J: ATC(N). (local[S] (Unit) => Int / (J[Int] => J[Bool]))))) ="""
    run(source(1).replace("val callback =", annotation), "true")
  }
  test("inspection reports symbolic annotated certificates without inventing a numeric depth") {
    val p = Parser.parse(source(1)).toOption.get
    val c = AtmBenchmark.inspect(p).fold(e => fail(e.message), identity)
    assertEquals(c.atcs, 1)
    assertEquals(c.distances, List(None))
    assertEquals(c.contexts.map(AtmBenchmark.depth), List(None))
  }
  test("a concrete explicit ATC can supply a missing inherited answer") {
    run("""def main(): Bool = {
      try {
        def local call(u: Unit) = { h.ask[refl; []]() }
        call()
      } with h: Bool {
        def ask(u: Unit): Int / (Int => Bool) = { val n = resume(42); n == 42 }
        return x => { x }
      }
    }""", "true")
  }
  test("computation kind abstraction forwards an abstract computation") {
    run("""def main(): Int = {
      val forward = typefun [C: Cty] {
        fun local(f: (local (Unit) => cty C)): cty C { f() }
      };
      cty Answer = Int;
      def local answer(u: Unit): Int = { 42 }
      forward[cty cty Answer](answer)
    }""", "42")
  }
  test("computation instantiation retains an impure answer contract") {
    run("""def main(): Bool = {
      val forward = typefun [R: Reg, C: Cty] {
        fun local[R](f: (local[R] (Unit) => cty C)): cty C { f() }
      };
      try {
        def local answer(u: Unit): Int / (Int => Bool) = { h.ask() }
        forward[region here][cty Int / (Int => Bool)](answer)
      } with h: Bool {
        def ask(u: Unit): Int / (Int => Bool) = { val n = resume(42); n == 42 }
        return x => { x }
      }
    }""", "true")
  }
  test("abstract ATC result projections report the remaining lowering restriction") {
    val p = checked("""def main(): Unit = {
      val forward = typefun [K: ATC(1)] {
        fun local(f: (local (Unit) => K[Int])): K[Int] { f() }
      };
      ()
    }""")
    Core.elaborate(p)
    val e = intercept[Diagnostic](effekt.atm.LiftedLowering(p))
    assertEquals(e.kind, DiagnosticKind.Unsupported)
    assert(e.message.contains("known value projection"))
  }
  test("wrong evidence endpoints are rejected in the abstract body") {
    rejected(source(1).replace("Ev(R, D, outer)", "Ev(outer, D, R)"), "incorrect region endpoints")
  }
  test("a symbolic context at the wrong depth is rejected") {
    rejected(source(1).replace("K: ATC(D)", "K: ATC(D + 1)"), "ATC depth differs")
  }
  test("a context argument must satisfy the instantiated distance") {
    rejected(source(1).replace("[atc Int / (Int => [])]", "[atc []]"), "Annotation kind mismatch")
  }
  test("evidence arguments cannot invent a delimiter crossing") {
    rejected(source(1).replace("[evidence step(h1)]", "[evidence refl]"), "Annotation kind mismatch")
    rejected(source(1).replace("[distance 1]", "[distance 0]"), "Annotation kind mismatch")
  }
  test("evidence composition requires matching intermediate regions") {
    rejected(source(1).replace("[evidence step(h1)]", "[evidence step(outer) + step(h1)]"), "Ill-composed region evidence")
  }
  test("unknown annotations and forward kind dependencies are rejected") {
    rejected(source(1).replace("outer.ask[E; K]", "outer.ask[E; Missing]"), "Unknown or non-ATC")
    rejected("def main(): Unit = { typefun [K: ATC(D), D: Dist] { () }; () }", "Unknown or non-distance")
  }
  test("shadowing a region with a distance does not reuse the old region") {
    rejected("def main(): Unit = { typefun [R: Reg] { typefun [R: Dist] { fun local[R](u: Unit): Unit { () } } }; () }", "not a region")
  }
  test("an ATC layer binder is visible in its initial answer") {
    run("""def main(): Unit = {
      val f = typefun [K: ATC(1)] { () };
      f[atc (x: Int) / ({v: Int | v == x} => [])]
    }""", "unit")
  }
  test("an ATC layer binder does not scope over subsequent layers") {
    rejected("""def main(): Unit = {
      val f = typefun [K: ATC(2)] { () };
      f[atc (x: Int) / (Int => Int / ({v: Int | v == x} => []))]
    }""", "Unknown refinement variable x")
  }
  test("kind abstraction does not hide an escaping handler region") {
    rejected("""def main(): Unit = {
      try { typefun [K: ATC(0)] { fun local[h](u: Unit): Int { 42 } } }
      with h: Unit {
        def unused(u: Unit): Unit / (Unit => Unit) = { resume() }
        return x => { () }
      }
    }""", "local handler region")
  }
  test("implicit functions cannot relabel a returned evidence kind's abstract caller") {
    rejected("""def main(): Unit = {
      def make(u: Unit) = { typefun [E: Ev(here, 0, top)] { () } }
      ()
    }""", "abstract caller region")
  }
  test("a context application preserves the operation's answer contract") {
    rejected(source(1).replace("K[Int] => K[Bool]", "K[Bool] => K[Bool]"), "Expected")
  }
  test("kinded type equality is alpha invariant and distances are additive") {
    import Annotations.Kind
    import Evidence.{Distance as D, AnswerContext as K}
    def scheme(r: String, d: String, k: String) = Type.KindPoly(r, Kind.Region,
      Type.KindPoly(d, Kind.Distance, Type.KindPoly(k, Kind.Context(D.Plus(D.Variable(d), D.Numeral(1))),
        Type.LocalFunction(Type.Unit, K.Variable(k).plug(Computation(Type.Int)), region = Some(r)))))
    assert(TypeEquality.same(scheme("R", "D", "K"), scheme("S", "N", "J")))
    assert(Evidence.sameDistance(D.Plus(D.Variable("D"), D.Numeral(1)), D.Plus(D.Numeral(1), D.Variable("D"))))
    assert(!Evidence.sameDistance(D.Variable("D"), D.Plus(D.Variable("D"), D.Numeral(1))))
  }
  test("Core independently rejects a mismatched symbolic ATC certificate") {
    val root = Core.elaborate(checked(source(1))).body
    def v(x: Core.Value): Core.Value = x.copy(node = x.node match {
      case Core.ValueNode.KindLambda(n, k, b) => Core.ValueNode.KindLambda(n, k, v(b))
      case Core.ValueNode.LocalLambda(n, r, b) => Core.ValueNode.LocalLambda(n, r, t(b))
      case other => other
    })
    def t(x: Core.Term): Core.Term = x.copy(node = x.node match {
      case Core.Node.Handle(r, e, b, n, ret, ops) => Core.Node.Handle(r, e, t(b), n, ret, ops)
      case Core.Node.Let(n, a, b) => Core.Node.Let(n, t(a), t(b))
      case Core.Node.Return(a) => Core.Node.Return(v(a))
      case Core.Node.Perform(r, op, a, e, typing) => Core.Node.Perform(r, op, a, e, typing.copy(context = Evidence.AnswerContext.Hole))
      case other => other
    })
    val error = intercept[Diagnostic](Core.validate(t(root)))
    assert(error.message.contains("ATC depth differs"), error.message)
  }

  private def positionalCall(annotations: String, aliases: String = ""): String = s"""def main(): Bool = {
    try {
      $aliases
      h.ask$annotations()
    } with h: Bool {
      def ask(u: Unit): Int / (Int => Bool) = { val n = resume(42); n == 42 }
      return x => { x }
    }
  }"""

  test("either operation annotation can be supplied or reconstructed independently") {
    for annotations <- List("[refl; []]", "[refl]", "[[]]", "") do
      run(positionalCall(annotations), "true")
  }

  test("legacy tagged operation annotations retain their executable meaning") {
    for annotations <- List("[evidence refl; atc []]", "[atc []; evidence refl]", "[evidence refl]", "[atc []]") do
      run(positionalCall(annotations), "true")
  }

  test("positional evidence and contexts still require valid endpoints and matching depths") {
    rejected(positionalCall("[step(h); []]"), "incorrect region endpoints")
    rejected(positionalCall("[refl; Int / (Int => [])]"), "ATC depth differs")
    rejected(positionalCall("[step(h) + step(h); []]"), "Ill-composed region evidence")
    rejected(positionalCall("[step(h)]"), "incorrect region endpoints")
    rejected(positionalCall("[Int / (Int => [])]"), "ATC depth differs")
  }

  test("single annotation aliases are classified by kind rather than spelling") {
    run(positionalCall("[e]", "atc e = [];"), "true")
    run(positionalCall("[C]", "evidence C = refl;"), "true")
    run(positionalCall("[x]", "evidence x = refl; atc x = [];"), "true")
    run(positionalCall("[x]", "atc x = []; evidence x = refl;"), "true")
  }

  test("single annotation names require an in-scope evidence or ATC kind") {
    rejected(positionalCall("[Missing]"), "Unknown operation annotation Missing")
    for alias <- List("distance X = 0;", "region X = here;", "cty X = Int;",
      "atc X = []; distance X = 0;") do
      rejected(positionalCall("[X]", alias), "must have evidence or ATC kind")
    rejected(positionalCall("[E; K]", "atc E = []; evidence K = refl;"), "non-evidence variable E")
  }

  test("single annotations accept evidence and context kind parameters") {
    run(positionalCall("", """val call = typefun [C: Ev(h, 0, h)] {
      fun local[h](u: Unit): Int / (Int => Bool) { h.ask[C]() }
    };""").replace("h.ask()", "call[evidence refl]()"), "true")
    run(positionalCall("", """val call = typefun [e: ATC(0)] {
      fun local[h](u: Unit): Int / (e[Int] => e[Bool]) { h.ask[e]() }
    };""").replace("h.ask()", "call[atc []]()"), "true")
  }

  test("single concrete contexts and evidence execute across nested handlers") {
    for (depth, annotation) <- List(
      1 -> "[step(h1)]",
      1 -> "[layer(Int, Int, [])]",
      2 -> "[step(h2) + step(h1)]",
      2 -> "[Int / (Int => Int / (Int => []))]") do {
      val oldCall = s"callback[region here][distance $depth][evidence ${if depth == 1 then "step(h1)" else "step(h2) + step(h1)"}][atc ${layers(depth, "[]")}]()"
      run(source(depth).replace(callback, "").replace(oldCall, s"outer.ask$annotation()"), "true")
    }
    val oldCall = "callback[region here][distance 2][evidence step(h2) + step(h1)][atc Int / (Int => Int / (Int => []))]()"
    run(source(2).replace(callback, "").replace(oldCall,
      "atc K = Int / (Int => []); atc J = Int / (Int => []); outer.ask[K[J]]()"), "true")
  }
}

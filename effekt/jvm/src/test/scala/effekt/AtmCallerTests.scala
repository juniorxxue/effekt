package effekt

import effekt.atm.{Annotations, Checker, Core, DirectBackend, Evidence, Parser, Syntax, TypeEquality}
import Syntax.*

class AtmCallerTests extends munit.FunSuite {
  private def checked(s: String) = Parser.parse(s).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def run(s: String, expected: String): Checker.CheckedProgram = {
    val p = checked(s)
    Core.elaborate(p)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
    p
  }
  test("a returned evidence telescope is indexed by the actual caller") {
    run("""def main(): Int = {
      val make = fun ()[R: Reg] { fun [E: Ev(R, 0, R)] { 42 } };
      val first = make()[refl];
      try { make()[refl] } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""", "42")
  }
  test("caller binders are alpha-equivalent but free regions remain distinct") {
    def f(caller: String, target: String) = Type.Function(Type.Unit,
      Computation(Type.KindPoly("E", Annotations.Kind.Evidence(caller, Evidence.Distance.Numeral(0), target), Type.Int)),
      region = Some(Evidence.Top), caller = Some(Caller(caller, "D")))
    assert(TypeEquality.same(f("r", "outer"), f("s", "outer")))
    assert(!TypeEquality.same(f("r", "outer"), f("s", "another")))
    assert(!TypeEquality.same(f("r", "r"), f("s", "r")))
    assertEquals(Annotations.regions(Computation(f("r", "outer"))), Set("outer"))
  }
  test("explicit call ATCs abstract over symbolic call distance") {
    run("""def main(): Bool = {
      try {
        val f = fun ()[K: ATC(dist(path(h)))]: Int / (K[Int] => K[Bool]) {
          h.ask[path(h); K]()
        };
        f[[]]()
      } with h: Bool {
        def ask(): Int / (Int => Bool) = resume(42) == 42
        return x => x
      }
    }""", "true")
  }

  test("reusable Fig. 1 with caller-indexed explicit ATC callbacks") {
    run(java.nio.file.Files.readString(java.nio.file.Path.of("examples/atm/file-reusable.effekt")), "42")
  }

  private val atTwoDepths = """def main(): Int = {
    try {
      val f = fun ()[K: ATC(dist(path(h)))]: Int / (K[Int] => K[Int]) {
        h.ask[path(h); K]()
      };
      val first = f[[]]();
      try { f[Int / (Int => [])]() } with inner: Int / (Int => Int) {
        def unused(): Unit / (Int / (Int => Int) => Int / (Int => Int)) = resume(())
        return x => x
      }
    } with h: Int {
      def ask(): Int / (Int => Int) = resume(42)
      return x => x
    }
  }"""
  test("one checked ATC-polymorphic function runs at two different depths") {
    run(atTwoDepths, "42")
    val inspection = Checker.inspect(Parser.parse(atTwoDepths).toOption.get)
    assert(inspection.result.isRight)
    assertEquals(inspection.operations.size, 1)
    assertEquals(inspection.operations.head.evidence.witness.distance.concrete, None)
  }
  test("call ATCs cannot erase a crossed handler") {
    val result = Parser.parse(atTwoDepths.replace("f[Int / (Int => [])]", "f[[]]")).flatMap(Checker.check)
    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains("Call-context ATC depth"))
  }
  test("call ATCs are mandatory and checked before application") {
    val result = Parser.parse(atTwoDepths.replace("f[[]]()", "f()")).flatMap(Checker.check)
    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains("expects 1 explicit ATC arguments"))
  }
  test("an unused ATC-polymorphic function still checks its body") {
    val result = Parser.parse("""def main(): Int = {
      val bad = fun ()[K: ATC(dist(path(top)))]: Int { true }; 0
    }""").flatMap(Checker.check)
    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains("Expected Int"))
  }

  test("ordinary fun checks against an explicit fixed-region contract") {
    run("""def main(): Int = {
      val f: ((Int) =>[top] Int) = fun (x) { x };
      f(42)
    }""", "42")
  }
  test("local is not a function or type keyword") {
    for s <- List(
      "def main(): Int = { val f = fun local (x: Int): Int { x }; f(42) }",
      "def main(): Int = { def local f(x: Int): Int = x; f(42) }",
      "def main(): Unit = { val f: (local (Int) => Int) = fun (x) { x }; () }") do
      assert(Parser.parse(s).isLeft)
    run("def main(): Int = { def local(x: Int): Int = x; local(42) }", "42")
  }
  test("recursive result families open at each caller") {
    run("""def main(): Int = {
      def rec make(n: Int)[R: Reg]: forall E: Ev(R, 0, R). (Int) = {
        if (n == 0) { fun [E: Ev(R, 0, R)] { 42 } } else { make(n - 1) }
      }
      try { make(3)[refl] } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""", "42")
  }
  test("nested caller binders remain distinct when their types are returned") {
    run("""def main(): Int = {
      val make = fun ()[R: Reg] { fun ()[R: Reg] { fun [E: Ev(R, 0, R)] { 42 } } };
      val f = make();
      try { f()[refl] } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""", "42")
  }
  test("Core rejects a forged call-context argument independently") {
    val p = checked("""def main(): Int = {
      val f = fun ()[K: ATC(dist(path(top)))]: Int { 42 };
      f[[]]()
    }""")
    val root = Core.elaborate(p).body
    def corrupt(t: Core.Term): Core.Term = t.node match {
      case Core.Node.Let(n, a, b) => t.copy(node = Core.Node.Let(n, a, corrupt(b)))
      case Core.Node.Apply(f, a, e, _) => t.copy(node = Core.Node.Apply(f, a, e,
        List(Evidence.AnswerContext.Layer(Type.Int, Computation(Type.Int), Evidence.AnswerContext.Hole, None))))
      case Core.Node.Ascribe(b) => t.copy(node = Core.Node.Ascribe(corrupt(b)))
      case _ => fail("Expected a call in the fixture")
    }
    val error = intercept[Diagnostic](Core.validate(corrupt(root)))
    assert(error.message.contains("incorrect call-context depth"))
  }

  test("call ATCs do not scope over ordinary argument contracts") {
    val result = Parser.parse("""def main(): Int = {
      try {
        val invoke = fun (f: ((Unit) => Int / (K[Int] => K[Int])))
          [K: ATC(dist(path(h)))]: Int / (K[Int] => K[Int]) { f() };
        invoke[[]](fun (): Int { 42 })
      } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""").flatMap(Checker.check)
    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains("K"))
  }


  test("ordinary domains retain their lexical region at different call sites") {
    run("""def main(): Int = {
      val answer = fun [E: Ev(top, 0, top)] { 42 };
      val invoke = fun (f: forall E: Ev(top, 0, top). (Int)) { f[refl(top)] };
      val first = invoke(answer);
      try { invoke(answer) } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""", "42")
  }
  test("no ordinary lambda form binds its caller in the argument domain") {
    val declarations = List(
      "val bad = fun (f: forall E: Ev(R, 0, R). (Int))[R: Reg] { f[refl] };",
      "val bad = fun (f: forall E: Ev(R, 0, R). (Int))[R: Reg]: Int { f[refl] };",
      "val bad: ((forall E: Ev(R, 0, R). (Int))[R: Reg] => Int) = fun (f) { f[refl] };",
      "def rec bad(f: forall E: Ev(R, 0, R). (Int))[R: Reg]: Int = { f[refl] };",
      "val bad = fun (f: forall E: Ev(R, 0, R). (Int))[R: Reg, K: ATC(dist(path(top)))]: Int { f[refl] };")
    for declaration <- declarations do {
      val parsed = Parser.parse("def main(): Int = { " + declaration + " 0 }").fold(e => fail(e.message), identity)
      val result = Checker.check(parsed)
      assert(result.isLeft, declaration)
      assert(result.swap.toOption.get.message.contains("Unknown or out-of-scope capability R"), result.toString)
    }
  }
  test("caller names in a forged domain are free and rejected by Core") {
    def f(r: String) = Type.Function(
      Type.KindPoly("E", Annotations.Kind.Evidence(r, Evidence.Distance.Numeral(0), r), Type.Int),
      Computation(Type.Int), region = Some(Evidence.Top), caller = Some(Caller(r, "D")))
    assert(!TypeEquality.same(f("r"), f("s")))
    assertEquals(Annotations.regions(Computation(f("r"))), Set("r"))
    val body = Core.Term(Core.Node.Return(Core.Value(Core.ValueNode.Integer(42), Type.Int)), Computation(Type.Int), "r")
    val abstraction = Evidence.FunctionAbstraction("r", Evidence.Binding("e",
      Evidence.Constraint("r", Evidence.Top), Evidence.Distance.Variable("D")))
    val value = Core.Value(Core.ValueNode.Lambda("x", abstraction, body), f("r"))
    val root = Core.Term(Core.Node.Return(value), Computation(value.tpe), Evidence.Top)
    val error = intercept[Diagnostic](Core.validate(root))
    assert(error.message.contains("Unbound annotation region r"))
  }
  test("a handler label names its region but not an ordinary function's caller") {
    run("""def main(): Int = {
      try {
        val same = fun [E: Ev(h, 0, h)] { 42 };
        same[refl(h)]
      } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""", "42")
    val result = Parser.parse("""def main(): Int = {
      try {
        val make = fun ()[R: Reg] { fun [E: Ev(R, 0, h)] { 42 } };
        try { make()[refl] } with inner: Int {
          def unused(): Unit / (Int => Int) = resume(())
          return x => x
        }
      } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""").flatMap(Checker.check)
    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains("Annotation kind mismatch"))
  }

  test("named caller types and checked lambdas alpha-rename the result family") {
    run("""def main(): Int = {
      val make: ((Unit)[R: Reg] => forall E: Ev(R, 0, R). (Int)) =
        fun (u)[S: Reg] { fun [E: Ev(S, 0, S)] { 42 } };
      val first = make()[refl(top)];
      try { make()[refl(h)] } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""", "42")
  }
  test("a named caller shadows an outer region only after the domain") {
    run("""def main(): Int = {
      region R = top;
      val answer = fun [E: Ev(top, 0, top)] { 42 };
      val invoke = fun (f: forall E: Ev(R, 0, R). (Int))[R: Reg]:
          forall E: Ev(R, 0, R). (Int) {
        fun [E: Ev(R, 0, R)] { f[refl(top)] }
      };
      try { invoke(answer)[refl(h)] } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""", "42")
  }
  test("the named caller is available in call-ATC kinds without an extra argument") {
    run("""def main(): Int = {
      val f = fun ()[R: Reg, K: ATC(dist(path(R)))]: Int / (K[Int] => K[Int]) { 42 };
      try { f[[]]() } with h: Int {
        def unused(): Unit / (Int => Int) = resume(())
        return x => x
      }
    }""", "42")
  }
  test("caller binders do not leak into following declarations") {
    val p = Parser.parse("""def main(): Int = {
      val f = fun ()[R: Reg] { 42 };
      val escaped = fun [E: Ev(R, 0, R)] { 0 };
      f()
    }""").fold(e => fail(e.message), identity)
    val error = Checker.check(p).swap.toOption.get
    assert(error.message.contains("Unknown or out-of-scope capability R"))
  }
  test("call telescopes reject ambiguous binders and fixed-region mixtures") {
    for signature <- List(
      "fun ()[R: Reg, S: Reg]: Int { 42 }",
      "fun ()[K: ATC(0), R: Reg]: Int { 42 }",
      "fun ()[R: Reg, R: ATC(0)]: Int { 42 }",
      "fun ()[D: Dist]: Int { 42 }",
      "fun ()[top: Reg]: Int { 42 }",
      "fun () at [top][R: Reg]: Int { 42 }") do
      assert(Parser.parse("def main(): Int = { val f = " + signature + "; 0 }").isLeft, signature)
  }
  test("here has no implicit region meaning and is an ordinary identifier") {
    run("def main(): Int = { val here = 42; here }", "42")
    run("""def main(): Int = {
      val make = fun ()[here: Reg] { fun [E: Ev(here, 0, here)] { 42 } };
      make()[refl]
    }""", "42")
    for use <- List("region R = here; 0", "val f = fun () at [here]: Int { 42 }; 0") do {
      val p = Parser.parse("def main(): Int = { " + use + " }").fold(e => fail(e.message), identity)
      val result = Checker.check(p)
      assert(result.isLeft)
      assert(result.swap.toOption.get.message.contains("Unknown or out-of-scope capability here"))
    }
    assert(Parser.parse("def main(): Unit = { capfun here (h: {}) { () }; () }").isLeft)
  }
}

package effekt

import effekt.atm.{Checker, Core, DirectBackend, Parser, Syntax}
import java.nio.file.{Files, Path}

class AtmPolymorphicSyntaxTests extends munit.FunSuite {
  private def source = Files.readString(Path.of("examples/atm/annotations/polymorphic-outer-call.effekt"))
  private val call = "callback[Int / (Int => [])]()"
  private def checked(s: String): Checker.CheckedProgram =
    Parser.parse(s).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def run(s: String, expected: String): Checker.CheckedProgram = {
    val p = checked(s)
    Core.elaborate(p)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
    p
  }
  private def reject(s: String, reason: String): Unit = {
    val parsed = Parser.parse(s).fold(e => fail(e.message), identity)
    Checker.check(parsed) match {
      case Left(e) => assert(e.message.contains(reason), e.message)
      case Right(_) => fail("Accepted an invalid polymorphic application")
    }
  }

  test("bare call ATCs preserve the tagged program's Core") {
    val plain = run(source, "true")
    val legacy = run(source.replace(call, "callback[atc Int / (Int => [])]()"), "true")
    assertEquals(Core.show(Core.elaborate(plain)), Core.show(Core.elaborate(legacy)))
  }
  test("bare arguments can be chained across lines") {
    run(source.replace(call, "callback\n[Int / (Int => [])]()"), "true")
  }
  test("the checked function type selects kinds through aliases") {
    run(source.replace(call, """atc Context = Int / (Int => []);
      val invoke = callback;
      invoke[Context]()"""), "true")
  }
  test("plain fun supports type parameters and following value parameters") {
    run("""def main(): Int = {
      val id = fun [A](x: A): A { x };
      if (id[Bool](true)) id[Int](42) else 0
    }""", "42")
  }
  test("legacy prefix words can themselves name bare arguments") {
    run("""def main(): Int = {
      val use = fun [R: Reg, D: Dist, E: Ev(R, D, R), K: ATC(D), C: Cty] {
        fun (x: Int) at [R]: Int { x }
      };
      region region = top; distance distance = 0;
      evidence evidence = refl; atc atc = []; cty cty = Int;
      use[region][distance + 0][evidence][atc[[]]][cty](42)
    }""", "42")
  }
  test("bare dependent ATC layers retain refinement binding scope") {
    run("""def main(): Unit = {
      val accept = fun [K: ATC(1)] { () };
      accept[(x: Int) / ({v: Int | v == x} => [])]
    }""", "unit")
    reject("""def main(): Unit = {
      val accept = fun [K: ATC(2)] { () };
      accept[(x: Int) / (Int => {v: Int | v == x} / (Int => []))]
    }""", "Unknown refinement variable x")
  }
  test("mixed value types and dependent kinds instantiate left to right") {
    run("""def main(): Int = {
      val id = fun [A, R: Reg, D: Dist, E: Ev(R, D, R), K: ATC(D)] {
        fun (x: A) at [R]: A { x }
      };
      id[Int, top, 0, refl, []](42)
    }""", "42")
  }
  test("zero is interpreted by the expected distance or evidence kind") {
    run("""def main(): Int = {
      val id = fun [R: Reg, D: Dist, E: Ev(R, D, R)] {
        fun (x: Int) at [R]: Int { x }
      };
      id[top, 0, 0](42)
    }""", "42")
  }
  test("computation arguments use their scoped kind without a cty prefix") {
    run("""def main(): Int = {
      val forward = fun [R: Reg, C: Cty] {
        fun (f: ((Unit) =>[R] C)) at [R]: C { f() }
      };
      cty Answer = Int;
      def answer() at [top]: Int = { 42 }
      forward[top, Answer](answer)
    }""", "42")
  }
  test("concrete computation arguments preserve their answer effects") {
    run("""def main(): Bool = {
      val forward = fun [R: Reg, C: Cty] {
        fun (f: ((Unit) =>[R] C)) at [R]: C { f() }
      };
      try {
        def answer() at [h]: Int / (Int => Bool) = { h.ask() }
        forward[h, Int / (Int => Bool)](answer)
      } with h: Bool {
        def ask(): Int / (Int => Bool) = { val n = resume(42); n == 42 }
        return x => { x }
      }
    }""", "true")
  }
  test("effect and predicate arguments also use the quantified parameter") {
    run("""def main(): Int = {
      val byEffect = fun [F: Eff] { 42 };
      val byPredicate = predfun [P(Int)] { fun (x: {v: Int | P(v)}): Int { x } };
      byPredicate[(x: Int) => x == 42](byEffect[pure])
    }""", "42")
  }
  test("bare evidence still checks its endpoints and depth") {
    reject(source.replace("outer.ask[path(outer); K]", "outer.ask[refl; K]"), "incorrect region endpoints")
  }
  test("bare contexts still check their depth") {
    reject(source.replace("callback[Int / (Int => [])]", "callback[[]]"), "Call-context ATC depth")
  }
  test("kind shadowing cannot turn a distance into a region") {
    reject("""def main(): Unit = {
      val use = fun [S: Reg] { () };
      fun [R: Reg] { fun [R: Dist] { use[R] } };
      ()
    }""", "not a region")
  }
  test("region arguments cannot be used as value types") {
    reject("""def main(): Int = {
      val id = fun [A](x: A): A { x };
      region R = top;
      id[R](42)
    }""", "not a value type")
  }
  test("ordinary calls still report symbolic-distance ATC failure") {
    val p = Parser.parse(Files.readString(Path.of("examples/atm/infer/polymorphic-outer-call.effekt"))).toOption.get
    val e = Checker.check(p).swap.toOption.get
    assertEquals(e.kind, Syntax.DiagnosticKind.Unsupported)
    assert(e.message.contains("ATC inference requires a concrete evidence distance"))
  }
}

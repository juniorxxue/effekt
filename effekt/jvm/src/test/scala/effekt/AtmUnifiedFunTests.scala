package effekt

import effekt.atm.{Checker, Core, DirectBackend, Parser, Syntax}
import java.nio.file.{Files, Path}

class AtmUnifiedFunTests extends munit.FunSuite {
  private val contract = "{ def ask(): Int / (Int => Int); }"
  private def handler(body: String): String = s"""try { $body } with h: Int {
    def ask(): Int / (Int => Int) = resume(42)
    return x => x
  }"""
  private def run(source: String, expected: String = "42"): Checker.CheckedProgram = {
    val p = Parser.parse(source).flatMap(Checker.check).fold(e => fail(e.message), identity)
    Core.elaborate(p)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
    p
  }
  private def reject(source: String, reason: String): Unit = {
    val result = Parser.parse(source).flatMap(Checker.check)
    assert(result.isLeft, "Accepted an invalid program")
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
  }

  test("a capability parameter uses a dependent arrow and ordinary application") {
    run(s"""def main(): Int = {
      val forward: (c: $contract) => cap(c) = fun (d: $contract) { d };
      ${handler("val saved = forward(h); saved.ask()")}
    }""")
  }
  test("capability application works through an ordinary higher-order parameter") {
    run(s"""def main(): Int = {
      def id(f: (c: $contract) => cap(c)) = f
      val forward = id(fun (d: $contract) { d });
      ${handler("val saved = forward(h); saved.ask()")}
    }""")
  }
  test("named capability functions bind the same abstract region") {
    run(s"""def forward(c: $contract) = c
      def main(): Int = ${handler("val saved = forward(h); saved.ask()")}""")
  }
  test("a capability parameter can precede a curried ordinary parameter list") {
    run(s"""def main(): Int = {
      val ask = fun (c: $contract) () at [c]: Int / (Int => Int) { c.ask() };
      ${handler("ask(h)()")}
    }""")
  }
  test("capability application inside a value abstraction checks the callee type") {
    run(s"""def main(): Int = {
      val forward = fun (c: $contract) { c };
      val again = fun (d: $contract) { forward(d) };
      ${handler("val saved = again(h); saved.ask()")}
    }""")
  }
  test("a computed capability argument is evaluated exactly once") {
    run(s"""def main(): Int = {
      val forward = fun (c: $contract) { c };
      try {
        val saved = forward({ val ignored = h.ask(); h });
        saved.ask()
      } with h: Int {
        def ask(): Int / (Int => Int) = resume(0) + 1
        return x => x
      }
    }""", "2")
  }
  test("nested capability functions use chained ordinary calls") {
    run(s"""def main(): Int = {
      val second = fun (c: $contract) { fun (d: $contract) { d } };
      ${handler("val saved = second(h)(h); saved.ask()")}
    }""")
  }
  test("a capability argument still requires a compatible operation contract") {
    reject(s"""def main(): Int = {
      val forward = fun (c: $contract) { c };
      try { val ignored = forward(h); 42 } with h: Int {
        def ask(): Bool / (Int => Int) = resume(true)
        return x => x
      }
    }""", "different operation or answer contract")
  }
  test("unused capability function bodies are checked") {
    reject(s"""def main(): Int = {
      val broken = fun (c: $contract) { unknownValue };
      42
    }""", "Unknown value unknownValue")
  }
  test("ordinary capability calls retain handler escape checking") {
    reject(s"""def main(): Int = {
      val forward = fun (c: $contract) { c };
      try { forward(h) } with h: Int {
        def ask(): Int / (Int => Int) = resume(42)
        return ignored => 42
      }
    }""", "local handler region")
  }
  test("effect kind binders accept pure and changing answer effects") {
    run("""def main(): Int = {
      val constant: forall E: Eff. (Int) = fun [F: Eff] { 21 };
      constant[pure] + constant[(Int => Bool)]
    }""")
  }
  test("value types effects and evidence kinds share the fun parameter list") {
    run("""def main(): Int = {
      val id = fun [A, E: Eff, R: Reg](x: A): A { x };
      id[Int, (Int => Bool), top](42)
    }""")
  }
  test("effect parameters work on named functions too") {
    run("""def identity[E: Eff](x: Int): Int = x
      def main(): Int = identity[pure](42)""")
  }
  test("an effect parameter is substituted in a higher-order callback contract") {
    run(Files.readString(Path.of("examples/atm/effect-parameter.effekt")))
  }
  test("an effect parameter cannot be instantiated with a value type") {
    reject("""def main(): Int = {
      val constant = fun [E: Eff] { 42 };
      constant[Int]
    }""", "Unknown answer-effect variable Int")
  }
  test("effect kind shadowing does not make a region argument an effect") {
    reject("""def main(): Int = {
      val constant = fun [E: Eff] { 42 };
      region E = top;
      constant[E]
    }""", "Unknown answer-effect variable E")
  }
}

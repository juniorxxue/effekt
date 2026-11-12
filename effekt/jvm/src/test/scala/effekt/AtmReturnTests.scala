package effekt

import effekt.atm.{Checker, Core, Parser}
import java.nio.file.{Files, Path}

class AtmReturnTests extends munit.FunSuite {
  private def check(source: String): Checker.CheckedProgram =
    Parser.parse(source).flatMap(Checker.check).fold(e => fail(e.message), identity)

  private def run(source: String, expected: String): Unit = {
    val p = check(source)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(AtmBackend.run(p, optimized).fold(fail(_), identity), expected)
  }

  private def reject(source: String, diagnostic: String): Unit = {
    val parsed = Parser.parse(source).fold(e => fail(e.message), identity)
    Checker.check(parsed) match {
      case Left(e) => assert(e.message.contains(diagnostic), e.message)
      case Right(_) => fail("Unexpectedly accepted an invalid return clause")
    }
  }

  private def outerOperation(keyword: String): String =
    Files.readString(Path.of("examples/atm/return-effects.effekt"))
      .replace("return effect x", s"$keyword x")

  private def dependentReturn(keyword: String): String = s"""def main(): Int = {
    try {
      try { inner.choose() } with inner: Int / ({v: Int | v == 42} => Int) {
        def choose(u: Unit): (choice: Int) /
          (Int / ({v: Int | v == choice} => Int) => Int / ({v: Int | v == 42} => Int)) = {
          resume(42)
        }
        $keyword x => { outer.echo(x) }
      }
    } with outer: Int {
      def echo(n: Int): Int / ({v: Int | v == n} => Int) = { resume(n) }
      return y => { 42 }
    }
  }"""

  for keyword <- List("return", "return effect") do {
    test(s"$keyword: an outer operation inherits the return contract's final answer") {
      run(outerOperation(keyword), "false")
    }
    test(s"$keyword: a dependent initial answer is instantiated with the return binder") {
      run(dependentReturn(keyword), "42")
    }
    test(s"$keyword: a mismatched dependent answer is rejected") {
      reject(dependentReturn(keyword).replace("outer.echo(x)", "outer.echo(x + 1)"), "Expected")
    }
    test(s"$keyword: a wrong return value type is rejected") {
      reject(outerOperation(keyword).replace("outer.ask()", "true"), "Expected")
    }
    test(s"$keyword: the exited handler capability remains out of scope") {
      reject(outerOperation(keyword).replace("outer.ask()", "inner.tick()"), "Unknown or out-of-scope")
    }
    test(s"$keyword: a pure expected return contract still selects empty mode") {
      reject(s"""def main(): Int = {
        try {
          try { () } with inner: Int {
            $keyword x => { outer.ask() }
          }
        } with outer: Int {
          def ask(u: Unit): Int / (Int => Int) = { resume(42) }
          return x => { x }
        }
      }""", "inherited final answer")
    }
  }

  test("ordinary and compatibility return spellings elaborate to the same Core") {
    assertEquals(Core.elaborate(check(outerOperation("return"))).body,
      Core.elaborate(check(outerOperation("return effect"))).body)
  }

  test("a pure return body still satisfies an impure contract after an outer operation") {
    run("""def main(): Bool = {
      try {
        try { outer.ask() } with inner: Int / (Int => Bool) {
          def unused(u: Unit): Unit / (Int / (Int => Bool) => Int / (Int => Bool)) = { resume() }
          return x => { x }
        }
      } with outer: Bool {
        def ask(u: Unit): Int / (Int => Bool) = {
          val n = resume(42);
          n == 42
        }
        return x => { x }
      }
    }""", "true")
  }
}

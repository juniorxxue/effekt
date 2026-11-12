package effekt

import effekt.atm.{Checker, Parser}
import java.nio.file.{Files, Path}

class AtmCapabilityNamesTests extends munit.FunSuite {
  private def program(body: String, before: String = ""): String = s"""def main(): Int = {
    $before
    try { $body } with h: Int {
      def ask(u: Unit): Int / (Int => Int) = { resume(42) }
      return x => { x }
    }
  }"""

  private def run(source: String, expected: String): Unit = {
    val parsed = Parser.parse(source).fold(e => fail(e.message), identity)
    val checked = Checker.check(parsed).fold(e => fail(e.message), identity)
    assertEquals(AtmReference.run(parsed), expected)
    assertEquals(AtmBackend.run(checked, true).fold(fail(_), identity), expected)
  }

  private def reject(source: String, reason: String): Unit = {
    val parsed = Parser.parse(source).fold(e => fail(e.message), identity)
    val result = Checker.check(parsed)
    assert(result.isLeft, "Ill-typed capability use accepted")
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
  }

  test("a handler name is a value argument and cap(h) remains a type annotation") {
    run(program("""def invoke(c: cap(h)) at [h]: Int / (Int => Int) = { c.ask() }
      invoke(h)"""), "42")
  }

  test("capability abstraction accepts a bare handler name and its parameter is a value") {
    run(program("val saved = forward{h}; saved.ask()", """
      val forward = capfun (c: { def ask(Unit): Int / (Int => Int); }) { c };
    """), "42")
  }

  test("a saved capability retains its handler when another handler reuses its name") {
    run(program("""val saved = h;
      val inner = try { val current = h; current.ask() } with h: Int {
        def ask(u: Unit): Int / (Int => Int) = { resume(1) }
        return x => { x }
      };
      val outer = saved.ask();
      add(inner, outer)"""), "43")
  }

  test("a local value shadows a capability in value position") {
    run(program("val h = 7; h"), "7")
  }

  test("a function parameter shadows a capability in value position") {
    run(program("def echo(h: Int) at [h]: Int = { h } echo(7)"), "7")
  }

  test("an inner handler shadows an outer value in value and receiver positions") {
    run(program("val direct = h.ask(); val saved = h; val indirect = saved.ask(); add(direct, indirect)",
      "val h = 7;"), "84")
  }

  test("a capability parameter shadows an outer value") {
    run(program("val saved = forward{h}; saved.ask()", """
      val c = 7;
      val forward = capfun (c: { def ask(Unit): Int / (Int => Int); }) { c };
    """), "42")
  }

  test("a value shadow cannot be used as the old capability receiver") {
    reject(program("val h = 7; h.ask()"), "non-capability type")
  }

  test("a value shadow cannot supply the old capability to a polymorphic call") {
    reject(program("val h = 7; val saved = forward{h}; saved.ask()", """
      val forward = capfun (c: { def ask(Unit): Int / (Int => Int); }) { c };
    """), "Capability instantiation expects a capability")
  }

  test("a bare capability cannot escape through a handler's return clause") {
    reject("""def main(): Int = {
      try { h } with h: Int {
        def ask(u: Unit): Int / (Int => Int) = { resume(42) }
        return ignored => { 42 }
      }
    }""", "return-clause parameter type mentions the local handler region")
  }

  test("legacy explicit capability values still compile and execute") {
    run(program("val saved = cap(h); saved.ask()"), "42")
  }

  test("maintained capability examples use bare names through both backends") {
    for (name, expected) <- List("capability-argument" -> "false", "capability-polymorphic" -> "true") do
      run(Files.readString(Path.of("examples/atm", name + ".effekt")), expected)
  }
}

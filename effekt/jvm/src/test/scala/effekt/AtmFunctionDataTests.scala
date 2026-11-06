package effekt

import effekt.atm.{Checker, Parser}
import effekt.atm.Syntax.*

class AtmFunctionDataTests extends munit.FunSuite {
  private def check(s: String) = Parser.parse(s).flatMap(Checker.check)
  private val data = """data Box { Box(index: Int, run: (local (Unit) => {v: Int | v == index})) }
    measure index(b: Box): Int { case Box(n,f) => n; }
  """
  test("dependent function fields survive construction, matching, and both backends") {
    val source = data + """def main(): {v: Int | v == 7} = {
      val b = Box(7, fun local (u: Unit): {v: Int | v == 7} { 7 });
      match (b) { case Box(n,f) => { f() } }
    }"""
    val checked = check(source).fold(e => fail(e.message), identity)
    assertEquals(AtmReference.run(checked.source), "7")
    for optimized <- List(false, true) do assertEquals(AtmBackend.run(checked, optimized).fold(fail(_), identity), "7")
  }
  test("constructor fields must satisfy dependent contracts") {
    val result = check(data + """def main(): Unit = {
      val b = Box(7, fun local (u: Unit): Int { 8 }); ()
    }""")
    assert(result.isLeft); assertEquals(result.swap.toOption.get.kind, DiagnosticKind.ContractFailure)
  }
  test("logical function projection cannot justify runtime function equality") {
    val result = check(data + """def main(): Bool = {
      val b = Box(7, fun local (u: Unit): {v: Int | v == 7} { 7 }); b == b
    }""")
    assert(result.isLeft); assert(result.swap.toOption.get.message.contains("first-order"))
  }
  test("a continuation from another region cannot inhabit a top-region field") {
    val result = check("""data Holder { Hold(run: (local (Unit) => Int)) }
    def main(): Unit = {
      try {
        try { inner.op() } with inner: Unit {
          def op(u: Unit): Unit / (Int => Unit) = {
            val b = Hold(fun local (u: Unit): Int { resume() }); ()
          }
          return u => { 0 }
        }
      } with outer: Unit {
        def unused(u: Unit): Unit / (Unit => Unit) = { resume() }
        return u => { u }
      }
    }""")
    assert(result.isLeft)
    assertEquals(result.swap.toOption.get.kind, DiagnosticKind.TypeError)
    assertEquals(result.swap.toOption.get.message, "A_KApp: continuation application requires exactly its ambient region")
  }

  test("refined scalar fields are enforced without any use-site refinements") {
    val result = check("data Positive { Pos(n: {v: Int | v > 0}) } def main(): Unit = { val p = Pos(0); () }")
    assert(result.isLeft); assertEquals(result.swap.toOption.get.kind, DiagnosticKind.ContractFailure)
  }
}

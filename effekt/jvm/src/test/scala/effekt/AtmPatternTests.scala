package effekt

import effekt.atm.{Parser, Checker, Core}
import effekt.atm.Syntax.*

class AtmPatternTests extends munit.FunSuite {
  private val data = "data ListInt { Nil(); Cons(head: Int, tail: ListInt) }\n"
  private def checked(s: String) = Parser.parse(s).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def rejects(s: String, message: String): Unit = {
    val result = Parser.parse(data + s).flatMap(Checker.check)
    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains(message), result.toString)
  }
  test("constructor branches retain exact scrutinee and field refinements") {
    val p = checked(data + """def main(): {r: Int | r == 42} = {
      def get(xs: {v: ListInt | v == Cons(42, Nil())}): {r: Int | r == 42} = {
        match (xs) { case Nil() => { 0 } case Cons(h, t) => { h } }
      }
      get(Cons(42, Nil()))
    }""")
    assertEquals(AtmReference.run(p.source), "42")
    for optimized <- List(false, true) do assertEquals(AtmBackend.run(p, optimized).fold(fail(_), identity), "42")
  }
  test("recursive list functions match without weakening their universal contracts") {
    val p = checked(data + """def main(): Int = {
      def rec length(xs: ListInt): {r: Int | r >= 0} = {
        match (xs) {
          case Nil() => { 0 }
          case Cons(h, t) => { val n = length(t); n + 1 }
        }
      }
      length(Cons(1, Cons(2, Nil())))
    }""")
    assertEquals(AtmReference.run(p.source), "2")
    for optimized <- List(false, true) do assertEquals(AtmBackend.run(p, optimized).fold(fail(_), identity), "2")
  }
  test("inferred match results close their local pattern names") {
    val p = checked(data + """def main(): Int = {
      val n = match (Cons(42, Nil())) { case Nil() => { 0 } case Cons(h, t) => { h } };
      n
    }""")
    assertEquals(AtmBackend.run(p, true).fold(fail(_), identity), "42")
  }
  test("match cases may perform checked operations") {
    val p = checked(data + """def main(): Bool = {
      try { match (Cons(1, Nil())) {
        case Nil() => { h.ask() }
        case Cons(x, xs) => { h.ask() }
      } } with h: Bool {
        def ask(u: Unit): Int / (Int => Bool) = { val n = resume(42); zero(n) }
        return x => { x }
      }
    }""")
    assertEquals(AtmReference.run(p.source), "false")
    for optimized <- List(false, true) do assertEquals(AtmBackend.run(p, optimized).fold(fail(_), identity), "false")
  }
  test("a reachable branch cannot claim another constructor's refinement") {
    rejects("""def main(): Int = {
      def wrong(xs: ListInt): {r: Int | r == 42} = {
        match (xs) { case Nil() => { 0 } case Cons(h, t) => { h } }
      }
      wrong(Nil())
    }""", "Expected")
  }
  test("matches must be exhaustive") {
    rejects("def main(): Int = { match (Nil()) { case Nil() => { 0 } } }", "cover every constructor")
  }
  test("matches reject duplicate constructor clauses") {
    rejects("def main(): Int = { match (Nil()) { case Nil() => { 0 } case Nil() => { 1 } case Cons(h,t) => { h } } }", "Duplicate match")
  }
  test("matches reject incorrect field arity") {
    rejects("def main(): Int = { match (Nil()) { case Nil() => { 0 } case Cons(h) => { h } } }", "pattern fields")
  }
  test("matches reject repeated field names") {
    rejects("def main(): Int = { match (Nil()) { case Nil() => { 0 } case Cons(x,x) => { 0 } } }", "Duplicate pattern")
  }
}

package effekt

import effekt.atm.{Checker, Parser, Core}
import effekt.atm.Syntax.*

class AtmMeasureTests extends munit.FunSuite {
  private val data = "data ListInt { Nil(); Cons(head: Int, tail: ListInt) }\n"
  private val length = """measure length(xs: ListInt): Int {
    case Nil() => 0;
    case Cons(h, t) => 1 + length(t);
  }
  """
  private def checked(s: String) = Parser.parse(s).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def rejects(s: String, message: String): Unit = {
    val r = Parser.parse(data + s).flatMap(Checker.check)
    assert(r.isLeft); assert(r.swap.toOption.get.message.contains(message), r.toString)
  }
  test("a structural measure verifies a recursive implementation for arbitrary lists") {
    val p = checked(data + length + """def main(): {r: Int | r == 2} = {
      def rec size(xs: ListInt): {r: Int | r == length(xs)} = {
        match (xs) { case Nil() => { 0 } case Cons(h,t) => { val n = size(t); n + 1 } }
      }
      size(Cons(4, Cons(5, Nil())))
    }""")
    assert(Core.show(Core.elaborate(p)).contains("measure length"))
    assertEquals(AtmReference.run(p.source), "2")
    for optimized <- List(false, true) do assertEquals(AtmBackend.run(p, optimized).fold(fail(_), identity), "2")
  }
  test("structural measures can return data and take additional parameters") {
    val p = checked(data + """measure append(xs: ListInt, ys: ListInt): ListInt {
      case Nil() => ys;
      case Cons(h,t) => Cons(h, append(t, ys));
    }
    def main(): {r: Bool | r == true} = {
      def rec cat(args: (ListInt, ListInt)): {r: ListInt | r == append(fst(args), snd(args))} = {
        match (fst(args)) {
          case Nil() => { snd(args) }
          case Cons(h,t) => { val rest = cat((t, snd(args))); Cons(h, rest) }
        }
      }
      val result = cat((Cons(1, Nil()), Cons(2, Nil())));
      result == Cons(1, Cons(2, Nil()))
    }""")
    for optimized <- List(false, true) do assertEquals(AtmBackend.run(p, optimized).fold(fail(_), identity), "true")
  }
  test("a wrong implementation cannot be justified by its measure") {
    rejects(length + """def main(): Int = {
      def size(xs: ListInt): {r: Int | r == length(xs)} = { 0 }
      size(Nil())
    }""", "Expected")
  }
  test("nondecreasing recursive definitions cannot introduce inconsistent axioms") {
    rejects(length.replace("1 + length(t)", "1 + length(xs)") + "def main(): Unit = { () }", "direct recursive constructor field")
  }
  test("logical binder shadowing cannot fake a smaller argument") {
    rejects(length.replace("1 + length(t)", "forall t: ListInt. length(t) == 0") + "def main(): Unit = { () }", "logical binders")
  }
  test("a structural definition requires exhaustive cases") {
    rejects("measure bad(xs: ListInt): Int { case Nil() => 0; } def main(): Unit = { () }", "cover each constructor")
  }
  test("measure results are sorted") {
    rejects(length.replace("1 + length(t)", "true") + "def main(): Unit = { () }", "wrong result sort")
  }
  test("pattern names cannot shadow the structural argument") {
    rejects(length.replace("Cons(h, t)", "Cons(h, xs)") + "def main(): Unit = { () }", "cannot shadow")
  }
}

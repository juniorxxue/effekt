package effekt

import effekt.atm.{Checker, Core, Evidence, Parser, Syntax}
import effekt.atm.Syntax.*
import java.nio.file.{Files, Path}

class AtmFixedFunctionTests extends munit.FunSuite {
  private def read(name: String) = Files.readString(Path.of("examples/atm", name + ".effekt"))
  private def checked(s: String) = Parser.parse(s).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def rejected(s: String, reason: String): Unit = {
    val p = Parser.parse(s).fold(e => fail(e.message), identity)
    val result = Checker.check(p)
    assert(result.isLeft, "Ill-typed local function accepted")
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
  }
  for (name, expected) <- List(
    "capability-argument" -> "false", "capability-polymorphic" -> "true",
    "effectful-callback" -> "false", "polymorphic-callback" -> "false",
    "stored-resumption" -> "42", "return-effects" -> "false",
    "effect-polymorphic" -> "false", "repeatable-effect" -> "88", "composed-effects" -> "200"
  ) do test(s"$name: checked repair preserves original behavior through both backends") {
    val original = Files.readString(Path.of("docs/atm/history/rejected-before-local", name + ".atm.txt"))
    val source = read(name)
    val p = checked(source)
    assertEquals(AtmReference.run(Parser.parse(original).fold(e => fail(e.message), identity)), expected)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(AtmBackend.run(p, optimized).fold(fail(_), identity), expected)
  }
  test("local function arguments can be inferred from a local expected arrow") {
    val p = checked("""def main(): Int = {
      def apply(f: ((Int) =>[top] Int)) at [top]: Int = { f(42) }
      apply(fun (x) { x })
    }""")
    assertEquals(AtmBackend.run(p, true).fold(fail(_), identity), "42")
  }
  test("capability subsumption renames local regions inside operation answers") {
    checked("""def main(): Unit = {
      val f: forall cap c: {
        def tick(Unit): Unit / (((Int) =>[c] Int) => ((Int) =>[c] Int));
      }. (Unit) = capfun (d: {
        def tick(Unit): Unit / (((Int) =>[d] Int) => ((Int) =>[d] Int));
      }) { () };
      ()
    }""")
  }
  test("a local function cannot be invoked below a new delimiter") {
    rejected("""def main(): Int = {
      def f(x: Int) at [top]: Int = { x }
      try { f(42) } with h: Int {
        def tick(u: Unit): Unit / (Int => Int) = { resume() }
        return x => { x }
      }
    }""", "exactly its declared region")
  }
  test("a local closure cannot be silently promoted to a region-polymorphic arrow") {
    rejected("""def main(): Int = {
      val f: ((Int) => Int) = fun (x: Int) at [top]: Int { x };
      f(42)
    }""", "Expected")
  }
  test("a handler-local function cannot escape its handler") {
    rejected("""def main(): ((Unit) =>[top] Int) = {
      try { fun (u: Unit) at [h]: Int / (Int => Int) { h.ask() } }
      with h: ((Unit) =>[top] Int) {
        def ask(u: Unit): Int / (Int => Int) = { resume(42) }
        return f => { f }
      }
    }""", "local handler region")
  }
  test("stored resumptions retain their exact call region after exposure as local arrows") {
    val source = read("stored-resumption").replace("saved(42)", """try { saved(42) } with inner: Int {
      def tick(u: Unit): Unit / (Int => Int) = { resume() }
      return x => { x }
    }""")
    rejected(source, "exactly its declared region")
  }
  test("answer-changing callbacks cannot be duplicated without composable endpoints") {
    rejected(read("effectful-callback").replace("f()", "{ val first = f(); f() }"), "Answer mismatch")
  }
  test("composition type parameters do not permit inconsistent callback endpoints") {
    rejected(read("composed-effects").replace("[type Int][type Bool][type Int]", "[type Int][type Int][type Int]"), "Expected")
  }
  test("explicit effectful return clauses still enforce their answer type") {
    rejected(read("return-effects").replace("outer.ask()", "true"), "Expected")
  }
  test("explicit return clauses cannot invoke the capability they have exited") {
    rejected(read("return-effects").replace("outer.ask()", "inner.tick()"), "Unknown or out-of-scope")
  }
  test("returned fixed-region arrows are indexed by the actual caller") {
    val p = checked("""def main(): Int = {
      def make(u: Unit)[R: Reg] = { fun (x: Int) at [R]: Int { x } }
      val f = make();
      f(42)
    }""")
    assertEquals(effekt.atm.DirectBackend.run(p).fold(fail(_), identity), "42")
  }
  test("an explicitly stable returned local arrow remains usable") {
    val p = checked("""def main(): Int = {
      def make(u: Unit)[R: Reg]: ((Int) =>[R] Int) = { fun (x: Int) at [R]: Int { x } }
      val f = make();
      f(42)
    }""")
    assertEquals(AtmBackend.run(p, true).fold(fail(_), identity), "42")
  }
  test("Core independently rejects a local lambda with a different body region") {
    val int = Computation(Type.Int)
    val body = Core.Term(Core.Node.Return(Core.Value(Core.ValueNode.Integer(42), Type.Int)), int, "unbound")
    val lambda = Core.Value(Core.ValueNode.FixedLambda("x", Evidence.Top, body),
      Type.FixedFunction(Type.Int, int, region = Some(Evidence.Top)))
    val root = Core.Term(Core.Node.Return(lambda), Computation(lambda.tpe), Evidence.Top)
    val error = intercept[Diagnostic](Core.validate(root))
    assert(error.message.contains("different region"))
  }
}

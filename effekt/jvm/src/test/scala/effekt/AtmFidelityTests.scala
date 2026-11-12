package effekt

import effekt.atm.*
import java.nio.file.{Files, Path}

class AtmFidelityTests extends munit.FunSuite {
  private def check(source: String): Checker.CheckedProgram =
    Parser.parse(source).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def run(source: String, expected: String): Unit = {
    val p = check(source)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
  }
  private def reject(source: String, diagnostic: String): Unit =
    Parser.parse(source).flatMap(Checker.check) match {
      case Left(e) => assert(e.message.contains(diagnostic), e.message)
      case Right(_) => fail("Unexpectedly accepted an invalid program")
    }
  for (name, result) <- List("operation-checked-lambda" -> "42", "mixed-guard-dependent" -> "1", "recursive-extra-capture" -> "42") do
    test(s"paper fidelity regression: $name") {
      run(Files.readString(Path.of("examples/atm/fidelity", name + ".effekt")), result)
    }

  test("mixed guarded join keeps the dependent returned-value assumption in either branch") {
    val source = Files.readString(Path.of("examples/atm/fidelity/mixed-guard-dependent.effekt"))
    run(source.replace("h.left() } else { h.right()", "h.right() } else { h.left()"), "1")
    reject(source.replace("(x: {z: Int | z == 0})", "(x: Int)"), "Mixed guarded")
  }
  test("contextual type instantiation still cannot export a handler capability") {
    reject("""def main(): Int = {
      val id = typefun [A] { fun (x: A): A { x } };
      try { id[type cap(h)](cap(h)) } with h: Int {
        def ask(u: Unit): Int / (Int => Int) = { resume(0) }
        return x => { 0 }
      }
    }""", "local handler region")
  }
  private val variantCapability = """def main(): Int = {
    val alias = fun (c: { def ask(x: {v: Int | v >= 0}): Int / (Int => Int); }) { cap(c) };
    try { val target = alias(h); target.ask(0) } with h: Int {
      def ask(x: Int): {v: Int | v == 42} / (Int => Int) = { resume(42) }
      return x => { x }
    }
  }"""
  test("S_Cap permits a broader argument and a stronger operation result") {
    run(variantCapability, "42")
  }
  test("S_Cap rejects an operation whose argument precondition is stronger") {
    reject(variantCapability.replace("def ask(x: Int): {v: Int | v == 42}",
      "def ask(x: {v: Int | v > 0}): {v: Int | v == 42}"), "different operation or answer contract")
  }
  test("S_Cap rejects an operation whose result guarantee is weaker") {
    reject(variantCapability.replace("def ask(x: {v: Int | v >= 0}): Int",
      "def ask(x: {v: Int | v >= 0}): {v: Int | v == 43}"), "different operation or answer contract")
  }
  test("A_AbsChk obtains an omitted domain from the expected operation parameter") {
    val source = Files.readString(Path.of("examples/atm/fidelity/operation-checked-lambda.effekt"))
    run(source.replace("fun (x: Int): Int { x }", "fun (x) { x }"), "42")
    reject(source.replace("fun (x: Int): Int { x }", "fun (x) { true }"), "Expected")
  }
  test("Unit refinements and universal formulas are checked by the solver") {
    run("""def main(): {u: Unit | u == () && (forall x: Int. x + 0 == x)} = { () }""", "unit")
    reject("""def main(): {u: Unit | forall x: Int. x > 0} = { () }""", "Expected")
    reject("""def main(): {u: Unit | forall x: Int. x && true} = { () }""", "sort Bool")
  }
  test("logical quantifiers scope and rename independently of outer names") {
    run("""def main(): {v: Int | v == 42} = {
      val x = 42;
      val y: {v: Int | v == x && (forall x: Int. x == x)} = x;
      y
    }""", "42")
  }

  test("handler return checking propagates an expected arrow to an unannotated lambda") {
    run("""def main(): Int = {
      val f = try { 0 } with h: ((Int) => Int) {
        def unused(u: Unit): Int / (((Int) => Int) => ((Int) => Int)) = { resume(0) }
        return x => { fun (y) { y + 1 } }
      };
      f(41)
    }""", "42")
  }

  test("capture metadata is absent from type identity but region identity remains exact") {
    import Syntax.*
    val f: Type.Function = Type.Function(Type.Int, Computation(Type.Int), Set.empty, Some("r"))
    assert(TypeEquality.same(f, f.copy(captures = Set("extra"))))
    assert(!TypeEquality.same(f, f.copy(region = Some("other"))))
    def scheme(r: String): Type = Type.CapabilityPoly(r,
      List(Signature("op", Type.Unit, Type.Int, Computation(f.copy(region = Some(r))), Computation(Type.Int))),
      Type.Capability(r))
    assert(TypeEquality.same(scheme("r"), scheme("s")))
  }

  test("universal formulas compare modulo their local binder names") {
    import Syntax.*
    import Logic.Expr
    def t(n: String): Type = Type.Refined("v", Type.Unit,
      Expr.Forall(n, Logic.Sort.Int, Expr.App("==", List(Expr.Variable(n), Expr.Variable(n)))))
    assert(TypeEquality.same(t("x"), t("y")))
    val q = t("y").asInstanceOf[Type.Refined]
    assert(!TypeEquality.same(t("x"), q.copy(predicate = Expr.Forall("y", Logic.Sort.Int,
      Expr.App("==", List(Expr.Variable("y"), Expr.Variable("free")))))))
  }
  test("a capability quantifier binds its region in operation signatures") {
    val p = check("""def main(): Int = {
      val family = fun (c: { def op(((Unit) => Int) at {c}): Int / (Int => Int); }) { cap(c) };
      42
    }""")
    val scheme = p.body.children.head.values.head.tpe.asInstanceOf[Syntax.Type.CapabilityPoly]
    val arrow = scheme.operations.head.argument.asInstanceOf[Syntax.Type.Function]
    assertEquals(arrow.region, Some(scheme.parameter))
    Core.elaborate(p)
  }
  test("A_Handle checks an omitted result annotation against the expected type") {
    val source = Files.readString(Path.of("examples/atm/fidelity/operation-checked-lambda.effekt"))
    run(source.replace("with h: Int", "with h"), "42")
    reject(source.replace("with h: Int", "with h").replace("def main(): Int", "def main(): Bool"), "answer")
  }
  test("contextual handlers retain residuals for their enclosing region-polymorphic function") {
    run("""def main(): Int = {
      def id(x: Int): Int = { x }
      def f(u: Unit): Int = {
        try { id(42) } with h {
          def unused(u: Unit): Int / (Int => Int) = { resume(0) }
          return x => { x }
        }
      }
      f()
    }""", "42")
  }
}

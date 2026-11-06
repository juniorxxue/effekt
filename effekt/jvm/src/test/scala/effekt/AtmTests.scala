package effekt

import effekt.atm.{Checker, Evidence, Elaboration, Lowering, Parser, Syntax, TypeEquality}
import effekt.atm.Syntax.*
import effekt.atm.Evidence.*
import java.nio.file.{Files, Path}

/** Conformance regressions for the paper algorithm. The superseded tests for
 * stack lifting / repeatability / scoped tails are preserved in
 * docs/atm/history/AtmPrototypeTests.scala.txt, not silently treated as passing.
 */
class AtmTests extends munit.FunSuite {
  private def source(name: String): String = Files.readString(Path.of("examples/atm", s"$name.effekt"))
  private def original(name: String): String = Files.readString(Path.of("docs/atm/history/rejected-before-local", s"$name.atm.txt"))
  private def check(input: String): Checker.CheckedProgram =
    Parser.parse(input).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def reject(input: String, message: String): Unit = {
    val parsed = Parser.parse(input).fold(e => fail(e.message), identity)
    val result = Checker.check(parsed)
    assert(result.isLeft, "Expected rejection by the algorithm")
    assert(result.swap.toOption.get.message.contains(message), result.toString)
  }
  private def run(input: String, expected: String): Unit = {
    val p = check(input)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(AtmBackend.run(p, optimized).fold(fail(_), identity), expected)
  }

  for (name, expected) <- List("int-to-bool" -> "false", "nested-outer" -> "false",
    "file-protocol" -> "Open", "multishot" -> "true", "higher-order" -> "42",
    "return-clause" -> "true", "large-integer" -> "9007199254740995") do
    test(s"$name: paper checking and both native execution modes") { run(source(name), expected) }

  test("boolean operators preserve their meaning in v0.3.0 generated syntax") {
    run("""def main(): Bool = {
      val equal = true == true;
      val different = true != false;
      val neither = false || false;
      !false && equal && different && !neither
    }""", "true")
  }

  for name <- List("effectful-callback", "callback-under-handler", "capability-argument",
    "capability-polymorphic") do
    test(s"$name: outer-handler invocation in a function requires symbolic ATC and is rejected") {
      reject(if name == "callback-under-handler" then source(name) else original(name), "concrete evidence distance")
    }
  for name <- List("repeatable-effect", "composed-effects") do
    test(s"$name: prototype-only composition bounds are rejected") { reject(original(name), "prototype extensions") }
  test("scoped capability tails are not substituted for region constraints") {
    reject(source("multiple-capabilities"), "prototype extension")
  }
  test("opaque effects do not have a final-answer projection") {
    reject(original("effect-polymorphic"), "final-answer projection is undefined")
  }
  test("a continuation cannot be returned through an ordinary arrow") { reject(original("stored-resumption"), "~>") }
  test("return clauses have empty inherited-answer mode") { reject(original("return-effects"), "inherited final answer") }
  test("protocol violation is rejected at the ATC hole") { reject(source("file-protocol-invalid"), "requires answer Open") }
  test("unbound capability is rejected") { reject("def main(): Int = { missing.ask() }", "Unknown") }
  test("handler capability cannot escape") {
    reject("def main(): cap(h) = { 0 }", "Unknown capability type")
  }

  test("A_Refl uses zero distance and no residual") {
    val r = Evidence.reconstruct("a", "a", Nil, identity)
    assertEquals(r, Reconstruction(Witness.Refl("a"), Nil))
  }
  test("A_Step composes tagged paths without conflating equal-depth regions") {
    val ab = Binding("ab", Constraint("a", "b"), Distance.Numeral(1))
    val bc = Binding("bc", Constraint("b", "c"), Distance.Numeral(1))
    val r = Evidence.reconstruct("a", "c", List(ab, bc), identity)
    assertEquals(r.residuals, Nil)
    assertEquals(r.witness.distance.concrete, Some(2))
    assertEquals(r.witness.free, Set("ab", "bc"))
    assertEquals(Evidence.known("c", "a", List(ab, bc)), None)
  }
  test("A_Defer records the unresolved frontier, preserving the known prefix") {
    val ab = Binding("ab", Constraint("a", "b"), Distance.Numeral(1))
    val r = Evidence.reconstruct("a", "target", List(ab), _ => "deferred")
    assertEquals(r.residuals, List(Obligation("deferred", Constraint("b", "target"))))
    assertEquals(r.witness.distance.concrete, None)
    assertEquals(r.witness.constraint, Constraint("a", "target"))
  }
  test("minimum captured region follows reachability, and rejects incomparable targets") {
    val edge = Binding("edge", Constraint("inner", "outer"), Distance.Numeral(1))
    assertEquals(Evidence.minimum(Nil, Nil), Evidence.Top)
    assertEquals(Evidence.minimum(List("outer", "inner"), List(edge)), "inner")
    intercept[Diagnostic](Evidence.minimum(List("a", "b"), Nil))
  }
  test("ATC peels final-answer layers and preserves initial answers") {
    val initial = Computation(Type.Int)
    val finalType = Computation(Type.Bool)
    val inherited = Computation(Type.Unit, Effect.Change(initial, finalType))
    val ctx = Evidence.atc(inherited, finalType, Distance.Numeral(1), TypeEquality.same)
    assertEquals(ctx.plug(initial), Computation(Type.Unit, Effect.Change(initial, initial)))
    intercept[Diagnostic](Evidence.atc(inherited, finalType, Distance.Variable("d"), TypeEquality.same))
    intercept[Diagnostic](Evidence.atc(finalType, finalType, Distance.Numeral(1), TypeEquality.same))
  }

  test("A_AbsChk abstracts a fresh caller region and rewrites deferred call evidence") {
    val p = check("""def main(): Int = {
      def id(x: Int): Int = { x }
      def use(x: Int): Int = { id(x) }
      use(42)
    }""")
    val use = p.body.children(1).children.head.values.head
    val binding = use.abstraction.get.call
    val call = use.body.get
    assertEquals(call.evidence.head, Witness.Use(binding))
    assertEquals(call.residuals, Nil)
    assertEquals(binding.constraint.to, Evidence.Top)
    assertEquals(binding.distance.concrete, None)
    assert(p.elaborated.contains("Λ"))
    assert(p.elaborated.contains("evidence=callEvidence"))
    assertEquals(p.body.residuals, Nil)
  }
  test("A_AbsSyn infers the meet of two captured function regions (supplement p33)") {
    val input = """def main(): Int = {
      def outer(u: Unit): Unit = { () }
      try {
        def inner(u: Unit): Unit = { () }
        def both(u: Unit) = { outer(); inner() }
        both(); 42
      } with h: Int {
        def unused(u: Unit): Unit / (Int => Int) = { resume() }
        return x => { x }
      }
    }"""
    val p = check(input)
    val handler = p.body.children(1)
    val edge = handler.handlerEvidence.get
    val both = handler.children.head.children(1).children.head.values.head
    assertEquals(both.abstraction.get.call.constraint.to, edge.constraint.from)
    val calls = both.body.get.children
    assertEquals(calls.head.evidence.head.free, Set(both.abstraction.get.call.name, edge.name))
    assertEquals(calls(1).evidence.head.free, Set(both.abstraction.get.call.name))
    run(input, "42")
  }
  test("a function installing its own handler has concrete operation evidence") {
    run("""def main(): Int = {
      def f(u: Unit): Int = {
        try { h.ask() } with h: Int {
          def ask(u: Unit): Int / (Int => Int) = { resume(42) }
          return x => { x }
        }
      }
      f()
    }""", "42")
  }
  test("a continuation may be aliased and called at its exact ambient region") {
    run("""def main(): Int = {
      try { h.ask() } with h: Int {
        def ask(u: Unit): Int / (Int => Int) = { val next = resume; next(42) }
        return x => { x }
      }
    }""", "42")
  }
  test("a continuation cannot be called beneath another handler even when pure") {
    reject("""def main(): Int = {
      try { h.ask() } with h: Int {
        def ask(u: Unit): Int / (Int => Int) = {
          try { resume(42) } with inner: Int {
            def unused(u: Unit): Int / (Int => Int) = { resume(0) }
            return x => { x }
          }
        }
        return x => { x }
      }
    }""", "A_KApp")
  }
  test("nested clauses bind their own resume while an alias preserves the outer one") {
    run("""def main(): Int = {
      try {
        val n = outer.ask(); n + 1
      } with outer {
        def ask(u: Unit): Int / (Int => Int) = {
          val outerResume = resume;
          try {
            val n = inner.ask(); n + 10
          } with inner {
            def ask(u: Unit): Int / (Int => Int) = {
              val inside = resume(2);
              val outside = outerResume(40);
              inside + outside
            }
            return x => { x }
          }: Int
        }
        return x => { x }
      }
    }""", "53")
  }
  test("wrapping a continuation in an ordinary lambda does not make it region-polymorphic") {
    reject("""def main(): Int = {
      try { h.ask() } with h: Int {
        def ask(u: Unit): Int / (Int => Int) = {
          def wrapped(x: Int): Int = { resume(x) }
          wrapped(42)
        }
        return x => { x }
      }
    }""", "A_KApp")
  }
  test("an unused function body is checked at a fresh region") {
    reject("""def main(): Int = {
      try {
        def unused(u: Unit): Int / (Int => Int) = { h.ask() }
        42
      } with h: Int {
        def ask(u: Unit): Int / (Int => Int) = { resume(0) }
        return x => { x }
      }
    }""", "concrete evidence distance")
  }
  test("capability aliases preserve the exact handler identity") {
    val input = source("int-to-bool").replace("h.ask()", "{ val alias = cap(h); alias.ask() }")
    assert(input != source("int-to-bool"))
    run(input, "false")
  }
  test("source assertions cannot assume their conclusion") {
    reject("def main(): Unit = { assert(false) }", "not valid")
  }
  test("non-capability variables shadow handler names") {
    reject("""def main(): Int = {
      try { val h = 0; h.ask() } with h: Int {
        def ask(u: Unit): Int / (Int => Int) = { resume(42) }
        return x => { x }
      }
    }""", "non-capability")
  }
  test("elaboration rejects forged unbound evidence before lowering") {
    val p = check("def main(): Int = { 42 }")
    val forged = p.body.copy(evidence = List(Witness.Use(Binding("unknown", Constraint(Evidence.Top, Evidence.Top), Distance.Numeral(0)))))
    intercept[Diagnostic](Elaboration.validate(forged))
  }
  test("region quantification captures residuals and instantiation supplies their evidence") {
    val local = Obligation("local", Constraint("r", "outer"))
    val inherited = Obligation("inherited", Constraint("another", "outer"))
    val (bound, remaining) = Evidence.generalize("r", List(local, inherited))
    assertEquals(bound, List(local))
    assertEquals(remaining, List(inherited))
    val edge = Binding("actualPath", Constraint("actual", "outer"), Distance.Numeral(1))
    val supplied = Evidence.instantiate("r", "actual", bound, List(edge), _ => "fresh")
    assertEquals(supplied.map(_.witness), List(Witness.Use(edge)))
    assertEquals(supplied.flatMap(_.residuals), Nil)
    val deferred = Evidence.instantiate("r", "caller", bound, Nil, _ => "fresh")
    assertEquals(deferred.flatMap(_.residuals), List(Obligation("fresh", Constraint("caller", "outer"))))
  }
  test("bounded capability schemes compare constraints up to their bound region names") {
    val a = Type.CapabilityPoly("a", Nil, Type.Capability("a"),
      obligations = List(Obligation("proof1", Constraint("a", Evidence.Top))))
    val b = Type.CapabilityPoly("b", Nil, Type.Capability("b"),
      obligations = List(Obligation("proof2", Constraint("b", Evidence.Top))))
    assert(TypeEquality.same(a, b))
    assert(!TypeEquality.same(a, b.copy(obligations = Nil)))
  }
  test("capability quantification substitutes generative region identities") {
    run("""def main(): Int = {
      val alias = capfun (c: { def ask(Unit): Int / (Int => Int); }) { cap(c) };
      try { val target = alias{cap(h)}; target.ask() } with h: Int {
        def ask(u: Unit): Int / (Int => Int) = { resume(42) }
        return x => { x }
      }
    }""", "42")
  }
  test("dependent computation annotations bind only the initial answer") {
    run("""def main(): Int = {
      try {
        h.ask(): (result: Int) / ({r: Int | r == result} => Int)
      } with h: Int {
        def ask(u: Unit): (result: Int) / ({r: Int | r == result} => Int) = { resume(42) }
        return x => { x }
      }
    }""", "42")
    reject("""def main(): Int = {
      val x: (result: Int) / (Int => {r: Int | r == result}) = 0;
      0
    }""", "Unknown refinement variable result")
  }
  test("dependent answer binders compare up to alpha-renaming") {
    def answer(n: String) = Computation(Type.Int, Effect.Change(
      Computation(Type.Refined("v", Type.Int, effekt.atm.Logic.eq(effekt.atm.Logic.Expr.Variable("v"), effekt.atm.Logic.Expr.Variable(n)))),
      Computation(Type.Int)), Some(n))
    assert(TypeEquality.same(answer("x"), answer("y")))
    assert(!TypeEquality.same(answer("x"), answer("y").copy(returned = None)))
  }
  test("selfified local names are closed by checked annotations before leaving a let") {
    val p = check("""def main(): {r: Int | r == 42} = {
      def f(x: Int) = { val y = x + 1; y }
      f(41)
    }""")
    val f = p.body.children.head.values.head.tpe.asInstanceOf[Type.Function]
    assertEquals(effekt.atm.Contracts.free(f.result), f.parameter.toSet)
    assert(p.elaborated.contains("Ascribe"))
  }

  test("dependent result binders are fresh at each application") {
    reject("""def main(): Int = {
      def produce(x: Int): (result: {v: Int | v == x}) / (Int => Int) = { x }
      try {
        val a = produce(1);
        val b = produce(2);
        assert(false);
        0
      } with h: Int {
        def unused(u: Unit): Int / (Int => Int) = { resume(0) }
        return x => { x }
      }
    }""", "not valid")
  }

  test("the handler return-clause type cannot mention its closed local region") {
    reject("""def main(): Int = {
      try { cap(h) } with h: Int {
        def unused(u: Unit): Int / (Int => Int) = { resume(0) }
        return ignored => { 42 }
      }
    }""", "return-clause parameter type")
  }

}

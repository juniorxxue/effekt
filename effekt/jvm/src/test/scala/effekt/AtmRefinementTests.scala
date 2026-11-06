package effekt

import effekt.atm.{Checker, Logic, Lowering, Parser, Syntax}
import java.nio.file.Files

class AtmRefinementTests extends munit.FunSuite {
  private def check(source: String): Checker.CheckedProgram =
    Parser.parse(source).flatMap(Checker.check(_)).fold(e => fail(e.message), identity)
  private def reject(source: String, message: String): Unit = {
    val parsed = Parser.parse(source).fold(e => fail(e.message), identity)
    val result = Checker.check(parsed)
    assert(result.isLeft, "Invalid contract accepted")
    assert(result.swap.toOption.get.message.contains(message), result.toString)
  }
  private def run(source: String, expected: String): Unit = {
    val checked = check(source)
    assertEquals(AtmReference.run(checked.source), expected)
    for optimized <- List(false, true) do
      assertEquals(AtmBackend.run(checked, optimized).fold(fail(_), identity), expected)
  }

  test("exact arithmetic proves a result refinement") {
    run("def main(): {v: Int | v == 42} = { 6 * 7 }", "42")
    reject("def main(): {v: Int | v == 43} = { 6 * 7 }", "Expected")
  }
  test("the documented refinement and polymorphism example runs") {
    run(Files.readString(java.nio.file.Path.of("examples/atm/refinement-polymorphic.effekt")), "42")
  }
  test("dependent function contracts are verified for every argument") {
    run("""def main(): {v: Int | v == 42} = {
      def inc(x: Int): {v: Int | v == x + 1} = { x + 1 }
      inc(41)
    }""", "42")
    reject("""def main(): Int = {
      def invalid(x: Int): {v: Int | v == x + 1} = { x + 2 }
      0
    }""", "Expected")
  }
  test("branch conditions discharge path-dependent refinements") {
    run("""def main(): {v: Int | v >= 0} = {
      def abs(x: Int): {v: Int | v >= 0} = {
        if (x >= 0) { x } else { 0 - x }
      }
      abs(-42)
    }""", "42")
  }
  test("branch assumptions cannot leak from a branch which was not taken") {
    reject("""def main(): Int = {
      def invalid(x: Int): {v: Int | v > 0} = {
        val a = if (x > 0) { x } else { x };
        a
      }
      0
    }""", "Expected")
  }
  test("shadowing does not redirect a resolved dependent contract") {
    reject("""def main(): Int = {
      def invalid(x: Int): {v: Int | v == x} = { val x = 0; x }
      0
    }""", "Expected")
  }
  test("division requires nonzero input and agrees with truncation toward zero") {
    run("""def main(): {v: Int | v == -2} = {
      def divide(x: {v: Int | v != 0}): {v: Int | v == 7 / x} = { 7 / x }
      divide(-3)
    }""", "-2")
    reject("def main(): Int = { 1 / 0 }", "nonzero")
  }
  test("type polymorphism checks once and instantiates at different base types") {
    run("""def main(): Int = {
      val identity = typefun [A] { fun (x: A): A { x } };
      val b = identity[type Bool](true);
      if (b) { identity[type Int](42) } else { 0 }
    }""", "42")
    reject("""def main(): Int = {
      val invalid = typefun [A] { fun (x: A): A { 0 } };
      0
    }""", "Expected A")
  }
  test("higher-rank type contracts are compared up to alpha-renaming") {
    run("""def main(): Int = {
      def use(id: forall type B. (((B) => B))): Int = { id[type Int](42) }
      use(typefun [A] { fun (x: A): A { x } })
    }""", "42")
  }
  test("predicate polymorphism checks a rigid predicate before instantiation") {
    run("""def main(): {v: Int | v >= 0} = {
      val identity = predfun [P(Int)] {
        fun (x: {v: Int | P(v)}): {v: Int | P(v)} { x }
      };
      identity[pred (n: Int) => n >= 0](42)
    }""", "42")
    reject("""def main(): Int = {
      val invalid = predfun [P(Int)] {
        fun (x: {v: Int | P(v)}): {v: Int | P(v)} { 0 }
      };
      0
    }""", "Expected")
  }
  test("predicate sorts and bound scopes are checked") {
    reject("""def main(): Int = {
      val id = predfun [P(Int)] { fun (x: {v: Int | P(v)}): Int { x } };
      val bad = id[pred (b: Bool) => b];
      0
    }""", "sorts do not match")
    reject("def main(): {v: Int | missing == v} = { 0 }", "Unknown refinement variable")
  }
  test("recursive definitions are checked against their declared inductive contract") {
    run("""def main(): {v: Int | v == 8} = {
      def rec count(n: {v: Int | v >= 0}): {v: Int | v == n} = {
        if (n == 0) { 0 } else { val smaller = count(n - 1); smaller + 1 }
      }
      count(8)
    }""", "8")
  }
  test("assertions must be proved and cannot introduce arbitrary assumptions") {
    run("def main(): Int = { val n = 42; assert(n > 0); n }", "42")
    reject("def main(): Int = { assert(false); 0 }", "assertion is not valid")
  }
  test("solver unknown is a checking failure") {
    val solver = new Logic.Solver {
      def prove(context: Logic.Context, conclusion: Logic.Expr): Logic.Verdict = Logic.Verdict.Unknown("test timeout")
    }
    val source = Parser.parse("def main(): {v: Int | v > 0} = { 42 }").toOption.get
    val result = Checker.check(source, solver)
    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains("test timeout"))
  }
  test("pairs and projections preserve relational refinements") {
    run("""def main(): {r: Int | r == 42} = {
      val p = (true, (41, 1));
      if (fst(p)) { fst(snd(p)) + snd(snd(p)) } else { 42 }
    }""", "42")
    reject("def main(): {r: Int | r == 1} = { snd((true, 2)) }", "Expected")
  }
  test("type polymorphism instantiates at closed function and refined types") {
    run("""def main(): {r: Int | r == 42} = {
      val id = typefun [A] { fun (x: A): A { x } };
      val f = id[type ((x: Int) => {r: Int | r == x + 1})](
        fun (x: Int): {r: Int | r == x + 1} { x + 1 });
      val n = f(41);
      id[type {r: Int | r == 42}](n)
    }""", "42")
  }
  test("type instantiation admits a capability type in its scope") {
    run("""def main(): Int = {
      val id = typefun [A] { fun (x: A): A { x } };
      try {
        val local = id[type cap(c)](cap(c));
        local.ask()
      } with c: Int {
        def ask(u: Unit): Int / (Int => Int) = { resume(0) }
        return x => { x }
      }
    }""", "0")
  }
  test("handler abort does not export impossible continuation facts") {
    reject("""def main(): Int = {
      val result = try { stop.abort() } with stop: Int {
        def abort(u: Unit): {r: Int | false} / (Int => Int) = { 0 }
        return r => { r }
      };
      assert(false);
      result
    }""", "assertion is not valid")
  }
  test("a recursive contract must hold for its implementation") {
    reject("""def main(): Int = {
      def rec count(n: {v: Int | v >= 0}): {v: Int | v == n} = {
        if (n == 0) { 1 } else { val smaller = count(n - 1); smaller + 1 }
      }
      0
    }""", "Expected")
  }
  test("dependent operation result binders are scoped to initial answers") {
    reject("""def main(): Int = {
      try { c.pick() } with c: Int {
        def pick(u: Unit): (choice: Int) / (Int => {r: Int | r == choice}) = { 0 }
        return r => { r }
      }
    }""", "Unknown refinement variable choice")
  }
  test("dependent operations verify the resumption contract for every argument") {
    run("""def main(): {r: Int | r == 42} = {
      try { val n = c.choose(); n + 1 } with c: {r: Int | r == 42} {
        def choose(u: Unit): (choice: Int) /
          ({r: Int | r == choice + 1} => {r: Int | r == 42}) = { resume(41) }
        return r => { r }
      }
    }""", "42")
    reject("""def main(): {r: Int | r == 42} = {
      try { val n = c.choose(); n + 1 } with c: {r: Int | r == 42} {
        def choose(u: Unit): (choice: Int) /
          ({r: Int | r == choice + 1} => {r: Int | r == 42}) = { resume(40) }
        return r => { r }
      }
    }""", "Expected")
  }
  test("lexical aliases preserve dependent binders and type-variable shadowing") {
    run("""def main(): {r: Int | r == 42} = {
      type A = Bool;
      type Increment = ((x: Int) => {r: Int | r == x + 1});
      val id = typefun [A] { fun (x: A): A { x } };
      val increment: Increment = fun (x: Int): {r: Int | r == x + 1} { x + 1 };
      val n = increment(41);
      id[type {r: Int | r == 42}](n)
    }""", "42")
    reject("""def main(): Int = {
      def invalid(x: Int): {r: Int | r == x} = {
        type R = {r: Int | r == x};
        val x = 0;
        val result: R = x;
        result
      }
      0
    }""", "Expected")
  }
  test("a missing Z3 executable cannot approve a refinement") {
    val source = Parser.parse("def main(): {r: Int | r > 0} = { 42 }").toOption.get
    val missing = Files.createTempDirectory("effekt-no-solver-").resolve("absent-z3").toString
    val result = Checker.check(source, effekt.atm.RefinementSolver.z3(missing))
    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains("Cannot run Z3"))
  }
  test("alpha-equivalence cannot confuse an outer dependency with an optional inner binder") {
    import Syntax.*
    import Logic.Expr
    def result(dependency: String) = Computation(Type.Refined("r", Type.Int,
      Logic.eq(Expr.Variable("r"), Expr.Variable(dependency))))
    val outer = Type.Function(Type.Int, Computation(
      Type.Function(Type.Int, result("x"))), parameter = Some("x"))
    val inner = Type.Function(Type.Int, Computation(
      Type.Function(Type.Int, result("b"), parameter = Some("b"))), parameter = Some("a"))
    val renamedOuter = Type.Function(Type.Int, Computation(
      Type.Function(Type.Int, result("a"), parameter = Some("unused"))), parameter = Some("a"))
    assert(!effekt.atm.TypeEquality.same(outer, inner))
    assert(effekt.atm.TypeEquality.same(outer, renamedOuter))
  }
  test("predicate-polymorphic higher-rank arguments are compared under rigid renamed predicates") {
    run("""def main(): {r: Int | r >= 0} = {
      def use(id: forall pred Q(Int). (({v: Int | Q(v)}) => {v: Int | Q(v)})):
        {r: Int | r >= 0} = { id[pred (n: Int) => n >= 0](42) }
      use(predfun [P(Int)] { fun (x: {v: Int | P(v)}): {v: Int | P(v)} { x } })
    }""", "42")
  }
  test("a type-polymorphic value survives an answer-changing handler") {
    run("""def main(): Int = {
      type Identity = forall type A. (((A) => A));
      val identity: Identity = typefun [B] { fun (x: B): B { x } };
      try {
        c.ask();
        identity
      } with c: Int {
        def ask(u: Unit): Unit / (Identity => Int) = {
          val id = resume();
          id[type Int](42)
        }
        return id => { id }
      }
    }""", "42")
  }
  test("refinements do not make a symbolic ATC distance concrete") {
    reject("""def main(): {r: Int | r == 42} = {
      val invoke = capfun (c: { def ask(u: Unit): {v: Int | v == 42} / ({r: Int | r == 42} => {r: Int | r == 42}); }) {
        fun (u: Unit): {v: Int | v == 42} / ({r: Int | r == 42} => {r: Int | r == 42}) { c.ask() }
      };
      try { invoke{cap(h)}() } with h: {r: Int | r == 42} {
        def ask(u: Unit): {v: Int | v == 42} / ({r: Int | r == 42} => {r: Int | r == 42}) = { resume(42) }
        return r => { r }
      }
    }""", "concrete evidence distance")
  }
}

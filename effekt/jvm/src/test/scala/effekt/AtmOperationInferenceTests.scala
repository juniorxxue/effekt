package effekt

import effekt.atm.{Checker, Core, DirectBackend, Parser, Syntax, TypeEquality}
import Syntax.*
import java.nio.file.{Files, Path}

class AtmOperationInferenceTests extends munit.FunSuite {
  private def parsed(source: String): Program = Parser.parse(source).fold(e => fail(e.message), identity)
  private def checked(source: String): Checker.CheckedProgram =
    Checker.check(parsed(source)).fold(e => fail(e.message), identity)
  private def run(source: String, expected: String): Checker.CheckedProgram = {
    val p = checked(source)
    Core.elaborate(p)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
    p
  }
  private def reject(source: String, reason: String): Unit = {
    val result = Checker.check(parsed(source))
    assert(result.isLeft, "Accepted an invalid operation clause")
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
  }
  private def simple(clause: String, body: String = "h.read()", before: String = ""): String =
    s"""def main(): Int = {
      $before
      try { $body } with h${if before.nonEmpty then ": Int" else ""} {
        $clause
        return x => { x }
      }
    }"""
  private val ordinary = "def read(): Int = { resume(42) }"

  private def operations(t: Checker.CheckedTerm): List[Checker.CheckedOperation] =
    t.operations ++ t.children.flatMap(operations) ++
      t.operations.flatMap(o => operations(o.body)) ++ t.values.flatMap(_.body.toList.flatMap(operations))

  test("the parser retains omitted contracts separately from explicit ones") {
    val Term.InferredHandle(_, inferred, _, _, _) = parsed(simple(ordinary)).body: @unchecked
    assertEquals(inferred.head.argument, Type.Unit)
    assertEquals(inferred.head.result, Type.Int)
    assertEquals(inferred.head.answers, None)
    val Term.InferredHandle(_, explicit, _, _, _) =
      parsed(simple("def read(): Int / (Int => Int) = { resume(42) }")).body: @unchecked
    assertEquals(explicit.head.answers, Some((Computation(Type.Int), Computation(Type.Int))))
  }

  test("a result-only zero-argument clause obtains the enclosing answer") {
    val p = run(simple(ordinary), "42")
    val op = operations(p.body).head.source
    assertEquals(op.initialAnswer, Computation(Type.Int))
    assertEquals(op.finalAnswer, Computation(Type.Int))
  }

  test("an empty argument binder does not capture an enclosing value") {
    run(simple("def read(): Int = { resume(u + argument) }", before = "val u = 20; val argument = 22;"), "42")
  }

  test("named arguments and pure primitive calls work with omitted answers") {
    run(simple("""def read(n: Int): Int = { val x = increment(n); resume(x) }""",
      "h.read(41)", "def increment(n: Int): Int = { n + 1 }"), "42")
  }

  test("inferred answers use the handler result rather than the operation result") {
    val p = run("""def main(): Bool = {
      try { h.read() } with h {
        def read(): Int = { resume(42) }
        return x => { x == 42 }
      }
    }""", "true")
    val op = operations(p.body).head.source
    assertEquals(op.result, Type.Int)
    assertEquals(op.initialAnswer, Computation(Type.Bool))
    assertEquals(op.finalAnswer, Computation(Type.Bool))
  }

  test("zero-argument clauses retain explicit answer-type modification") {
    run("""def main(): Bool = {
      try { h.read() } with h {
        def read(): Int / (Int => Bool) = { val n = resume(42); n == 42 }
        return x => { x }
      }
    }""", "true")
  }

  test("inferred and explicit operation contracts coexist") {
    run("""def main(): Bool = {
      try { h.tick(); h.read() } with h {
        def tick(): Unit = { resume() }
        def read(): Int / (Int => Bool) = { val n = resume(42); n == 42 }
        return x => { x }
      }
    }""", "true")
  }

  test("an inferred contract still checks the resumption argument") {
    reject(simple("def read(): Int = { resume(true) }"), "Expected Int")
  }

  test("an inferred contract still checks the clause answer") {
    reject(simple("def read(): Int = { true }"), "Expected Int")
  }

  test("omitting an answer-changing contract does not silently accept it") {
    reject("""def main(): Bool = {
      try { h.read() } with h {
        def read(): Int = { val n = resume(42); n + 1 }
        return x => { x == 42 }
      }
    }""", "expects Int")
  }

  test("dependent operation results with omitted answers retain refinement checks") {
    run(simple("def read(): (n: {v: Int | v == 42}) = { resume(42) }"), "42")
    reject(simple("def read(): (n: {v: Int | v == 42}) = { resume(41) }"), "Expected")
  }

  test("a resolved refinement answer is not resolved again under a clause binder") {
    run("""def main(): Int = {
      val n = 42;
      try { h.read(0) } with h {
        def read(n: Int): {v: Int | v == 42} = { resume(42) }
        return x => { x }
      }: {v: Int | v == n}
    }""", "42")
  }

  test("Fig. 1 executes with result-only read and close declarations") {
    val source = Files.readString(Path.of("examples/atm/file-nested.effekt"))
    val p = run(source, "42")
    val ops = operations(p.body).map(_.source)
    assertEquals(ops.size, 4)
    assert(ops.forall(_.answers.nonEmpty))
    val int = Computation(Type.Int)
    val inner = Computation(Type.Int, Effect.Change(int, int))
    assertEquals(ops.count(o => TypeEquality.same(o.initialAnswer, int)), 2)
    assertEquals(ops.count(o => TypeEquality.same(o.initialAnswer, inner)), 2)
  }

  test("Fig. 1 still checks the distance of outer-capability calls") {
    val source = Files.readString(Path.of("examples/atm/file-nested.effekt"))
    val explicit = source.replace("    }\n  } with a", "    }: Int\n  } with a")
    assertNotEquals(source, explicit)
    reject(explicit, "too few concrete layers")
  }
}

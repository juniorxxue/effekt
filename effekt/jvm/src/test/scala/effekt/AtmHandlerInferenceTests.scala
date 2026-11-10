package effekt

import effekt.atm.{Checker, Core, DirectBackend, Parser, Syntax, TypeEquality}
import Syntax.*
import java.nio.file.{Files, Path}

class AtmHandlerInferenceTests extends munit.FunSuite {
  private def parsed(s: String): Program = Parser.parse(s).fold(e => fail(e.message), identity)
  private def checked(s: String): Checker.CheckedProgram = Checker.check(parsed(s)).fold(e => fail(e.message), identity)
  private def run(s: String, expected: String): Checker.CheckedProgram = {
    val p = checked(s)
    Core.elaborate(p)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
    p
  }
  private def reject(s: String, reason: String): Unit = {
    val result = Checker.check(parsed(s))
    assert(result.isLeft, "Accepted an invalid inferred handler")
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
  }
  private def handlers(t: Checker.CheckedTerm): List[Computation] = {
    val here = t.source match {
      case Term.Handle(_, c, _, _, _, _) => List(c)
      case _ => Nil
    }
    here ++ t.children.flatMap(handlers) ++ t.operations.flatMap(o => handlers(o.body)) ++
      t.values.flatMap(_.body.toList.flatMap(handlers))
  }
  private val int = Computation(Type.Int)
  private val layer = Computation(Type.Int, Effect.Change(int, int))

  test("Fig. 1 needs no computation annotation on either handler") {
    val source = Files.readString(Path.of("examples/atm/file-nested.effekt"))
    assert(!source.contains("}:"))
    val p = run(source, "42")
    val hs = handlers(p.body)
    assertEquals(hs.size, 2)
    assert(hs.exists(TypeEquality.same(_, int)))
    assert(hs.exists(TypeEquality.same(_, layer)))
    val inspected = Checker.inspect(parsed(source))
    assert(inspected.result.isRight)
    assertEquals(inspected.operations.size, 4)
    assertEquals(inspected.operations.map(_.evidence.witness.distance.concrete),
      List(Some(1), Some(0), Some(1), Some(0)))
    val explicit = source.replace("    }\n  } with a", "    }: Int / (Int => Int)\n  } with a")
      .replace("  }\n}", "  }: Int\n}")
    assertNotEquals(source, explicit)
    assertEquals(AtmBenchmark.inspect(parsed(source)), AtmBenchmark.inspect(parsed(explicit)))
  }

  private def nested(body: String, onReturn: String = "x"): String = s"""def main(): Int = {
    try {
      val ok = try { $body } with inner {
        def unused(): Unit = { resume() }
        return x => { $onReturn }
      };
      if (ok) { 42 } else { 0 }
    } with outer {
      def read(): Int = { resume(42) }
      return x => { x }
    }
  }"""

  test("the handler result can differ from the surrounding answer value") {
    val p = run(nested("val n = outer.read(); n == 42"), "42")
    assert(handlers(p.body).exists(TypeEquality.same(_, Computation(Type.Bool, Effect.Change(int, int)))))
  }

  test("the return clause can change the handler's returned value type") {
    run(nested("outer.read()", "x == 42"), "42")
  }

  test("annotations accumulate through multiple nested delimiters") {
    val source = """def main(): Int = {
      try {
        try {
          try { outer.read() } with inner {
            def unused(): Unit = { resume() }
            return x => { x }
          }
        } with middle {
          def unused(): Unit = { resume() }
          return x => { x }
        }
      } with outer {
        def read(): Int = { resume(42) }
        return x => { x }
      }
    }"""
    val p = run(source, "42")
    val twice = Computation(Type.Int, Effect.Change(layer, layer))
    assert(handlers(p.body).exists(TypeEquality.same(_, twice)))
  }

  test("a pure handler in a let binding synthesizes its value type") {
    run("""def main(): Int = {
      val ok = try { h.read() } with h {
        def read(): Int = { resume(42) }
        return x => { x == 42 }
      };
      if (ok) { 42 } else { 0 }
    }""", "42")
  }

  test("annotated functions in a handler body contribute their declared result") {
    run("""def main(): Int = {
      val result = try {
        def increment(n: Int): Int = { n + 1 }
        val x = h.read();
        increment(x)
      } with h {
        def read(): Int = { resume(41) }
        return x => { x }
      };
      result
    }""", "42")
  }

  test("conditionals and matches infer a common value type") {
    run("""data Flag { Yes(); No() }
      def main(): Int = {
        val result = try {
          val n = h.read();
          if (n == 42) { Yes() } else { No() }
        } with h {
          def read(): Int = { resume(42) }
          return x => { match (x) { case Yes() => { 42 } case No() => { 0 } } }
        };
        result
      }""", "42")
  }

  test("local values shadow result headers just as they shadow capabilities") {
    run("""def main(): Int = {
      val x = try { val h = 42; h } with h {
        def unused(): Unit = { resume() }
        return x => { x }
      };
      x
    }""", "42")
  }

  test("the full check still rejects an invalid operation argument") {
    reject("""def main(): Int = {
      val x = try { h.read(true) } with h {
        def read(n: Int): Int = { resume(n) }
        return x => { x }
      };
      x
    }""", "Expected Int")
  }

  test("the full check still rejects an invalid clause answer") {
    reject("""def main(): Int = {
      val x = try { h.read() } with h {
        def read(): Int = { true }
        return x => { x }
      };
      x
    }""", "Expected Int")
  }

  test("a new capability cannot be mistaken for a shadowed outer value") {
    reject("""def main(): Int = {
      val h = 42;
      val escaped = try { h } with h {
        def unused(): Unit = { resume() }
        return x => { x }
      };
      0
    }""", "Unknown value h")
  }

  test("an explicit insufficient computation annotation is never repaired") {
    val source = Files.readString(Path.of("examples/atm/file-nested.effekt"))
    val explicit = source.replace("    }\n  } with a", "    }: Int\n  } with a")
    assertNotEquals(source, explicit)
    reject(explicit, "too few concrete layers")
  }

  test("a source abstraction without a result contract still needs an annotation") {
    reject("""def main(): Int = {
      val x = try { fun (n: Int) { n } } with h {
        def unused(): Unit = { resume() }
        return f => { 42 }
      };
      x
    }""", "add a computation annotation")
  }
}

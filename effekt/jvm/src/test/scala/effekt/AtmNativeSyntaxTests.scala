package effekt

import effekt.atm.{Checker, Core, DirectBackend, Parser, Syntax}
import Syntax.*

class AtmNativeSyntaxTests extends munit.FunSuite {
  private def checked(s: String): Checker.CheckedProgram =
    Parser.parse(s).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def run(s: String, expected: String): Unit = {
    val p = checked(s)
    Core.elaborate(p)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
  }
  private def reject(s: String, reason: String): Unit = {
    val result = Parser.parse(s).flatMap(Checker.check)
    assert(result.isLeft)
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
  }

  test("nested ordinary and resumption calls elaborate without value restrictions") {
    run("""interface Read { def read(): Int }
      def main(): Int = {
        def inc(x: Int): Int = x + 1
        try { inc(h.read()) } with h: Read { def read() = resume(inc(40)) }
      }""", "42")
  }
  test("calls in arithmetic and conditions retain left-to-right single evaluation") {
    run("""interface Emit { def emit(x: Int): Int }
      def main(): Int = {
        try { h.emit(1) + h.emit(2) } with h: Emit {
          def emit(x) / (Int => Int) = { val rest = resume(x); x * 100 + rest * 10 }
        }
      }""", "2400")
  }
  test("effectful boolean right operands remain conditional") {
    run("""interface Stop { def stop(): Bool }
      def main(): Int = {
        try {
          if ((false && h.stop()) || (true || h.stop())) 42 else 0
        } with h: Stop { def stop() / (Int => Int) = 0 }
      }""", "42")
  }
  test("constructors and grouped expressions accept computations") {
    run("""type Box { Box(n: Int) }
      def main(): Int = {
        def inc(x: Int): Int = x + 1
        val b = Box(inc(41))
        b match { case Box(n) => n }
      }""", "42")
  }
  test("native type and match spelling with multiline cases") {
    run("""type Choice {
        Yes(n: Int)
        No()
      }
      def main(): Int = {
        Yes(41) match {
          case Yes(n) =>
            val x = n + 1
            x
          case No() => 0
        }
      }""", "42")
  }
  test("native identifier suffixes and braceless branches") {
    run("""def main(): Int = {
      def valid?(n: Int): Bool = n == 42
      val answer$ = 42
      if (valid?(answer$)) answer$ else 0
    }""", "42")
  }
  test("nested computations remain checked against operation signatures") {
    reject("""interface Read { def read(): Int }
      def main(): Int = {
        def wrong(): Bool = true
        try { h.read() } with h: Read { def read() = resume(wrong()) }
      }""", "Expected Int")
  }

  test("multiple parameters and arguments preserve order across operation calls") {
    run("""interface Emit { def emit(n: Int): Int }
      def main(): Int = {
        def sum(a: Int, b: Int, c: Int): Int = a + b + c
        try { sum(h.emit(1), h.emit(2), h.emit(3)) } with h: Emit {
          def emit(x) / (Int => Int) = { val n = resume(x); x + n * 10 }
        }
      }""", "6321")
  }
  test("multiargument interfaces inherit signatures and check partial annotations") {
    run("""interface Sum { def sum(a: Int, b: Int, c: Int): Int }
      def main(): Int = {
        try { h.sum(10, 12, 20) } with h: Sum { def sum(x, y: Int, z) = resume(x + y + z) }
      }""", "42")
    reject("""interface Sum { def sum(a: Int, b: Int): Int }
      def main(): Int = { try { h.sum(1, 2) } with h: Sum { def sum(x, y: Bool) = resume(x) } }""", "Argument type")
  }
  test("multiargument interfaces retain dependent result guarantees") {
    run("""interface Sum { def sum(a: Int, b: Int): {v: Int | v == a + b} }
      def main(): Int = {
        try { h.sum(20, 22) } with h: Sum { def sum(x, y) = resume(x + y) }
      }""", "42")
  }
  test("ordinary recursive def uses the existing checked recursive function") {
    run("""def main(): Int = {
      def sum(n: Int, acc: Int): Int = if (n == 0) acc else sum(n - 1, acc + n)
      sum(8, 6)
    }""", "42")
  }
  test("a shadowed function name does not make a definition recursive") {
    run("""def main(): Int = {
      def f(f: Int) = f + 1
      f(41)
    }""", "42")
  }

  test("top-level functions, aliases and values support an inferred main result") {
    run("""type Number = Int
      def answer(): Number = increment(seed)
      def main() = answer()
      def increment(n: Number): Number = n + 1
      val seed = 41
    """, "42")
  }
  test("native polymorphic function declarations and explicit type arguments") {
    run("""def identity[A](x: A): A = x
      def main() = identity[Int](42)
    """, "42")
    reject("""def identity[A](x: A): A = x
      def main() = identity[Bool](42)
    """, "Expected Bool")
  }
  test("parameterized interfaces instantiate independently in nested handlers") {
    run("""interface Reader[A] { def read(): A }
      def main() = {
        try {
          try { if (b.read()) a.read() else 0 }
          with b: Reader[Bool] { def read() = resume(true) }
        } with a: Reader[Int] { def read() = resume(42) }
      }""", "42")
  }
  test("interface parameters shadow top-level aliases and remain checked") {
    run("""type A = Bool
      interface Reader[A] { def read(): A }
      def main() = try { h.read() } with h: Reader[Int] { def read() = resume(42) }
    """, "42")
    reject("""interface Reader[A] { def read(): A }
      def main() = try { h.read() } with h: Reader[Int] { def read() = resume(true) }
    """, "Expected Int")
  }
  test("generic interface arity and clause signatures cannot be ignored") {
    reject("""interface Reader[A] { def read(): A }
      def main() = try { h.read() } with h: Reader { def read() = resume(42) }
    """, "Wrong number of type arguments")
    reject("""interface Reader[A] { def read(): A }
      def main() = try { h.read() } with h: Reader[Int] { def read(): Bool = resume(true) }
    """, "Result type")
  }
  test("empty interfaces retain a real handler delimiter") {
    run("""interface Empty {}
      def main() = try { 42 } with h: Empty {}
    """, "42")
  }

  test("short circuiting also protects partial primitive expressions") {
    run("""def main() = if (true || (1 / 0 == 0)) 42 else 0""", "42")
    run("""def main() = if (false && (1 % 0 == 0)) 0 else 42""", "42")
  }
  test("native wildcard constructor fields do not bind or collide") {
    run("""type Triple { Triple(a: Int, b: Int, c: Int) }
      def main() = Triple(1, 2, 42) match { case Triple(_, _, answer) => answer }
    """, "42")
  }
  test("inferred closed results retain capability escape checks") {
    reject("""interface Read { def read(): Int }
      def main() = try { h } with h: Read { def read() = resume(42) }
    """, "Unknown value")
  }
  test("empty inner handlers still contribute to evidence distance") {
    val p = Parser.parse("""interface Read { def read(): Int }
      interface Empty {}
      def main() = {
        try { try { h.read() } with unused: Empty {} }
        with h: Read { def read() = resume(42) }
      }
    """).fold(e => fail(e.message), identity)
    val inspected = Checker.inspect(p)
    assert(inspected.result.isRight, inspected.result.toString)
    assertEquals(inspected.operations.map(_.evidence.witness.distance.concrete), List(Some(1)))
    assertEquals(AtmReference.run(p), "42")
  }

  test("computed conditions, return expressions and unary calls use expression elaboration") {
    run("""def negative(): Int = -42
      def choose(): Int = return if (true) -negative() else 0
      def main() = if (if (true) true else false) choose() else 0
    """, "42")
  }

  test("Fig. 1 continuation answers agree with the native Effekt comparison") {
    val source = java.nio.file.Files.readString(java.nio.file.Path.of("examples/atm/file-nested.effekt"))
    val traced = source
      .replace("resume(readInt(c))", "{ val n = resume(readInt(c)); n * 10 + 1 }")
      .replace("resume(readInt(d))", "{ val n = resume(readInt(d)); n * 10 + 2 }")
      .replace("closeFile(c); resume(())", "closeFile(c); val n = resume(()); n * 10 + 3")
      .replace("closeFile(d); resume(())", "closeFile(d); val n = resume(()); n * 10 + 4")
    assertNotEquals(source, traced)
    // Tags are appended when resumptions return, which depends on the nested
    // delimiters. Native Effekt/JS independently produces this same result.
    run(traced, "424231")
  }
}

package effekt

import effekt.atm.{Checker, Core, DirectBackend, Parser, Syntax}
import Syntax.*
import java.nio.file.{Files, Path}

class AtmInterfaceTests extends munit.FunSuite {
  private def parsed(s: String): Program = Parser.parse(s).fold(e => fail(e.message), identity)
  private def checked(s: String): Checker.CheckedProgram = Checker.check(parsed(s)).fold(e => fail(e.message), identity)
  private def run(s: String, expected: String): Unit = {
    val p = checked(s)
    Core.elaborate(p)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
  }
  private def reject(s: String, reason: String): Unit = {
    val result = Parser.parse(s).flatMap(Checker.check)
    assert(result.isLeft, "Accepted an invalid interface implementation")
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
  }
  private val file = """interface File {
    def read(): Int
    def close(): Unit
  }
  """
  private def program(clauses: String, body: String = "h.read()", name: String = "File"): String =
    file + s"def main(): Int = { try { $body } with h: $name { $clauses } }"
  private val clauses = "def read() = resume(42) def close() = resume()"

  test("native interface syntax inherits results and inserts the identity return") {
    run(program(clauses, "h.close(); h.read()"), "42")
  }
  test("explicit matching value signatures are accepted") {
    run(program("def read(): Int = resume(42) def close(): Unit = resume()"), "42")
  }
  test("a named argument inherits its interface type under a different binder") {
    run("""interface Add { def add(n: Int): Int }
      def main(): Int = { try { h.add(41) } with h: Add { def add(x) = resume(x + 1) } }""", "42")
  }
  test("newlines, expression function bodies and zero-argument local functions") {
    run("""interface Read { def read(): Int }
      def main(): Int = {
        def answer(): Int = 42
        val n = answer()
        try { h.read() } with h: Read { def read = resume(n) }
      }""", "42")
  }
  test("an explicit return clause and whole-handler computation annotation still work") {
    run(file + """def main(): Bool = {
      try { h.read() } with h: File {
        def read() = resume(42)
        def close() = resume()
        return n => n == 42
      }: Bool
    }""", "true")
  }
  test("interfaces leave explicit answer-changing contracts available") {
    run("""interface Read { def read(): Int }
      def main(): Bool = {
        try { h.read() } with h: Read {
          def read() / (Int => Bool) = { val n = resume(42); n == 42 }
        }
      }""", "true")
  }
  test("interfaces do not inherit a handler-local type alias") {
    run("""data Number { Num(n: Int) }
      interface Read { def read(): Number }
      def main(): Int = {
        type Number = Bool;
        val n = try { h.read() } with h: Read { def read() = resume(Num(42)) };
        match (n) { case Num(x) => { x } }
      }""", "42")
  }
  test("dependent interface signatures rename the declaration's argument") {
    run("""interface Identity { def get(n: Int): {v: Int | v == n} }
      def main(): Int = {
        try { h.get(42) } with h: Identity { def get(x) = resume(x) }
      }""", "42")
  }
  test("wrong clause results and resumption arguments are rejected") {
    reject(program("def read(): Bool = resume(true) def close() = resume()"), "Result type of read")
    reject(program("def read() = resume(true) def close() = resume()"), "Expected Int")
  }
  test("wrong argument annotations and arity are rejected") {
    val base = "interface Add { def add(n: Int): Int } def main(): Int = { try { h.add(42) } with h: Add { CLAUSE } }"
    reject(base.replace("CLAUSE", "def add(x: Bool) = resume(42)"), "Argument type of add")
    reject(base.replace("CLAUSE", "def add() = resume(42)"), "Wrong number of parameters")
    reject(program("def read(x: Unit) = resume(42) def close() = resume()"), "Wrong number of parameters")
  }
  test("unknown interfaces and incomplete or extra implementations are rejected") {
    reject(program(clauses, name = "Missing"), "Unknown interface Missing")
    reject(program("def read() = resume(42)"), "Missing operations")
    reject(program(clauses + " def extra(): Unit = resume()"), "not declared in interface File")
  }
  test("duplicate declarations and implementations are rejected") {
    reject(file + program(clauses), "Duplicate interface File")
    reject("interface Read { def read(): Int; def read(): Int } def main(): Int = 42", "Duplicate operation read")
    reject(program(clauses + " def read() = resume(42)"), "Duplicate operation in handler")
  }
  test("unused interface declarations are checked in their declaration scope") {
    reject("interface Bad { def read(): cap(h) } def main(): Int = 42", "Unknown capability type h")
  }
  test("ordinary structural handlers keep their existing spelling") {
    run("def main(): Int = { try { h.read() } with h: Int { def read(): Int = resume(42) } }", "42")
  }
  test("Fig. 1 restores File and keeps both nested computations inferred") {
    val source = Files.readString(Path.of("examples/atm/file-nested.effekt"))
    assert(source.contains("interface File"))
    assert(source.contains("with a: File"))
    assert(source.contains("with b: File"))
    assert(!source.contains("return x =>"))
    assert(!source.contains("}:"))
    run(source, "42")
  }
}

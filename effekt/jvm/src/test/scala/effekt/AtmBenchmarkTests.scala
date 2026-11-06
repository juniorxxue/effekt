package effekt

import effekt.atm.{Parser, Syntax}
import java.nio.file.{Files, Path}

class AtmBenchmarkTests extends munit.FunSuite {
  private def inspect(source: String) = AtmBenchmark.inspect(Parser.parse(source).fold(e => fail(e.message), identity))
  test("pure programs do not claim evidence or ATC inference was exercised") {
    assertEquals(inspect("def main(): Int = { 42 }"), Right(AtmBenchmark.Certificates()))
  }
  test("a contract rejection does not count as a complete inferred elaboration") {
    val result = inspect("def main(): {n: Int | n == 0} = { 1 }")
    assertEquals(result.swap.toOption.map(_.kind), Some(Syntax.DiagnosticKind.ContractFailure))
  }
  test("operation certificates include both hole and nonzero-depth ATCs") {
    for (name, layered) <- List("int-to-bool" -> false, "evidence-two-delimiters" -> true) do {
      val source = Files.readString(Path.of("examples/atm", name + ".effekt"))
      val c = inspect(source).fold(e => fail(e.message), identity)
      assert(c.evidence > 0 && c.atcs > 0)
      assertEquals(c.layeredAtcs > 0, layered)
    }
  }
}

package effekt

import effekt.atm.{Checker, Parser, Syntax}
import Syntax.*

class AtmParserTests extends munit.FunSuite {
  private def parse(source: String): Program = Parser.parse(source).fold(e => fail(e.message), identity)

  test("comments, negative exact integers and optional final semicolons") {
    val source = """// source comment
      def main(): Int / pure = {
        /* outer /* nested */ comment */
        -10000000000000000000000000000000000000000;
      } // trailing comment"""
    assertEquals(parse(source), Program(Computation(Type.Int),
      Term.Return(Value.Integer(BigInt("-10000000000000000000000000000000000000000")))))
  }

  test("local definitions desugar to the same nonrecursive function as val plus fun") {
    val definition = parse("""def main(): Int = {
      def identity(x: Int): Int = { x }
      identity(42)
    }""")
    val binding = parse("""def main(): Int = {
      val identity = fun (x: Int): Int { return x; };
      identity(42)
    }""")
    assertEquals(definition, binding)
    assert(Checker.check(definition).isRight)
    assertEquals(AtmReference.run(definition), "42")
  }

  test("empty argument lists and empty blocks denote Unit") {
    assertEquals(parse("def main(): Unit = {}"), parse("def main(): Unit = { () }"))
    assertEquals(parse("def main(): Int = { f() }"), parse("def main(): Int = { f(()) }"))
    assertEquals(parse("def main(): Int = { h.ask() }"), parse("def main(): Int = { h.ask(()) }"))
  }

  test("addition associates left and unary negation binds before addition") {
    val source = parse("def main(): Int = { (1 + 2) + -3 + 4 }")
    assertEquals(source.body, Term.Return(Value.Primitive("add", List(
      Value.Primitive("add", List(Value.Primitive("add", List(Value.Integer(1), Value.Integer(2))), Value.Integer(-3))),
      Value.Integer(4)))))
    assertEquals(AtmReference.run(source), "4")
    assertEquals(parse("def main(): Bool = { !false }").body,
      Term.Return(Value.Primitive("not", List(Value.Boolean(false)))))
  }

  test("capability and effect instantiation chain before ordinary application") {
    val body = parse("def main(): Int = { helper{cap(h)}[effect (E; F)](42) }").body
    assertEquals(body, Term.Apply(Value.InstantiateEffect(
      Value.Instantiate(Value.Variable("helper"), Value.Capability("h")),
      Effect.Sequence(Effect.Variable("E"), Effect.Variable("F"))), Value.Integer(42)))
  }

  test("function capture annotations and nested answer effects remain distinct") {
    val body = parse("""def main(): Int = {
      val f = fun (g: ((Unit) => Int / E) at {h}): Int / (Int / E => Bool) { g() };
      0
    }""").body
    val expected = Value.Lambda("g", Type.Function(Type.Unit, Computation(Type.Int, Effect.Variable("E")), Set("h")),
      Computation(Type.Int, Effect.Change(Computation(Type.Int, Effect.Variable("E")), Computation(Type.Bool))),
      Term.Apply(Value.Variable("g"), Value.Unit))
    assertEquals(body, Term.Let("f", Term.Return(expected), Term.Return(Value.Integer(0))))
  }

  test("transition arrows do not become function arrows after a grouped answer type") {
    val function = Type.Function(Type.Int, Computation(Type.Int))
    val source = parse("def main(): Int / (((Int) => Int) => ((Int) => Int)) = { 0 }")
    assertEquals(source.result.effect, Effect.Change(Computation(function), Computation(function)))
    val quantified = Type.EffectPoly("E", function)
    val polySource = parse("def main(): Int / ((forall effect E. (((Int) => Int))) => Bool) = { 0 }")
    assertEquals(polySource.result.effect, Effect.Change(Computation(quantified), Computation(Type.Bool)))
  }

  test("parenthesized answer transitions preserve nested computation endpoints") {
    val answer = Computation(Type.Int, Effect.Change(Computation(Type.Int), Computation(Type.Bool)))
    val result = parse("def main(): Unit / (Int / (Int => Bool) => Int / (Int => Bool)) = {}")
    assertEquals(result.result, Computation(Type.Unit, Effect.Change(answer, answer)))
    assertEquals(result, parse("def main(): Unit / [Int / [Int => Bool] => Int / [Int => Bool]] = {}"))
  }

  test("parenthesized transitions and grouped effect sequences remain distinct") {
    val transition = Effect.Change(Computation(Type.Int), Computation(Type.Bool))
    for (source, expected) <- List(
      "((Int => Bool))" -> transition,
      "(E; (Int => Bool))" -> Effect.Sequence(Effect.Variable("E"), transition),
      "((Int => Bool); F)" -> Effect.Sequence(transition, Effect.Variable("F")),
      "(pure)" -> Effect.Pure) do {
      assertEquals(parse(s"def main(): Int / $source = { 0 }").result.effect, expected)
    }
  }

  test("dependent and refined endpoints preserve the transition separator") {
    val source = "def main(): (x: Int) / ({r: Int | x == 0 => r >= 0} => Bool) = { 0 }"
    val legacy = "def main(): (x: Int) / [{r: Int | x == 0 => r >= 0} => Bool] = { 0 }"
    assertEquals(parse(source), parse(legacy))
    assertEquals(parse(source).result.returned, Some("x"))
  }

  test("effect instantiation keeps its brackets around a parenthesized transition") {
    assertEquals(parse("def main(): Int = { f[effect (Int => Bool)](42) }").body,
      Term.Apply(Value.InstantiateEffect(Value.Variable("f"),
        Effect.Change(Computation(Type.Int), Computation(Type.Bool))), Value.Integer(42)))
  }

  test("displayed answer effects put parentheses around the whole transition") {
    val computation = Computation(Type.Int, Effect.Change(Computation(Type.Int), Computation(Type.Bool)))
    assertEquals(Syntax.show(computation), "Int / (Int / pure => Bool / pure)")
    assertEquals(parse(s"def main(): ${Syntax.show(computation)} = { 0 }").result, computation)
  }

  test("parse errors identify the offending line and column") {
    assertEquals(Parser.parse("def main(): Int = {\n  @\n}").swap.toOption.get.message,
      "2:3: Unexpected character '@'")
    assertEquals(Parser.parse("def main(): Int = {\n  val x = 1 x\n}").swap.toOption.get.message,
      "2:13: Expected ';' or a newline")
    assertEquals(Parser.parse("/* unfinished").swap.toOption.get.message,
      "1:1: Unterminated block comment")
  }

  for (name, source) <- List(
    "outside a handler" -> "def main(): Int = { resume(42) }",
    "in a handler return clause" -> """def main(): Int = {
      try { 42 } with h {
        def ask(u: Unit): Int / (Int => Int) = { resume(0) }
        return x => { resume(x) }
      }
    }""") do test(s"resume is unbound $name") {
      val result = Checker.check(parse(source))
      assertEquals(result.swap.toOption.map(_.message), Some("Unknown value resume"))
    }

  test("resume remains reserved in user-written bindings") {
    for source <- List(
      "def main(): Int = { val resume = 42; resume }",
      "def main(): Int = { def f(resume: Int): Int = { resume } 0 }") do
      assert(Parser.parse(source).isLeft)
  }

  for (name, source) <- List(
    "legacy prefix syntax" -> "(program (pure Int) (return 1))",
    "trailing input" -> "def main(): Int = { 1 } ignored",
    "missing closing block" -> "def main(): Int = { 1",
    "missing answer endpoint" -> "def main(): Int / (Int => ) = { 1 }",
    "missing bound separator" -> "def main(): Int = { effectfun [E repeatable] { 1 } }",
    "redefining a reserved primitive" -> "def main(): Int = { val add = 0; add }") do {
    test(s"rejects $name") { assert(Parser.parse(source).isLeft) }
  }

  test("operation annotation positions and omissions agree with the legacy tagged forms") {
    def call(annotations: String) = parse(s"def main(): Int = { h.ask$annotations() }")
    for (positional, tagged) <- List(
      "[E; K]" -> "[evidence E; atc K]",
      "[E; K]" -> "[atc K; evidence E]",
      "[E;]" -> "[evidence E]",
      "[refl]" -> "[evidence refl]",
      "[[]]" -> "[atc []]",
      "[step(h)]" -> "[evidence step(h)]",
      "[layer(Int, Int, [])]" -> "[atc layer(Int, Int, [])]",
      "[K[J]]" -> "[atc K[J]]",
      "[; K]" -> "[atc K]") do assertEquals(call(positional), call(tagged))
  }

  test("single operation annotation names remain unresolved until kind checking") {
    for name <- List("K", "e", "evidence", "atc") do
      assertEquals(parse(s"def main(): Int = { h.ask[$name]() }").body,
        Term.SingleAnnotatedPerform("h", "ask", Value.Unit, name))
    assertEquals(parse("def main(): Int = { h.ask[(K)]() }").body,
      Term.SingleAnnotatedPerform("h", "ask", Value.Unit, "K"))
  }

  test("nested contexts and composed evidence stay inside their operation annotation slots") {
    def call(annotations: String) = parse(s"def main(): Int = { h.ask$annotations() }")
    assertEquals(call("[E + F; K[J[layer(Int, Int, [])]]]"),
      call("[evidence E + F; atc K[J[layer(Int, Int, [])]]]"))
    assertEquals(call("[refl; Int / (Int => Int / (Int => []))]"),
      call("[evidence refl; atc Int / (Int => Int / (Int => []))]"))
  }

  test("empty operation annotation brackets and extra positions are rejected") {
    for annotations <- List("[]", "[;]", "[E; K; J]", "[; K;]") do
      assert(Parser.parse(s"def main(): Int = { h.ask$annotations() }").isLeft, annotations)
  }
}

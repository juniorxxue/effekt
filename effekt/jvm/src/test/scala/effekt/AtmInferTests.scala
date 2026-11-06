package effekt

import effekt.atm.{Checker, Core, DirectBackend, Evidence, Parser, Syntax}
import effekt.atm.Syntax.*
import effekt.atm.Evidence.*
import java.nio.file.{Files, Path}

class AtmInferTests extends munit.FunSuite {
  private val names = List("active-three-counts", "active-three-multishot", "nested-residuals",
    "mixed-answer-layers", "shadowed-capabilities")
  private def source(name: String): String = Files.readString(Path.of("examples/atm/infer", name + ".effekt"))
  private def check(s: String): Checker.CheckedProgram =
    Parser.parse(s).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def valueTerms(v: Checker.CheckedValue): List[Checker.CheckedTerm] =
    v.body.toList.flatMap(terms) ++ v.children.flatMap(valueTerms)
  private def terms(t: Checker.CheckedTerm): List[Checker.CheckedTerm] =
    t :: (t.children.flatMap(terms) ++ t.operations.flatMap(o => terms(o.body)) ++ t.values.flatMap(valueTerms))
  private def values(v: Checker.CheckedValue): List[Checker.CheckedValue] =
    v :: (v.children.flatMap(values) ++ v.body.toList.flatMap(termValues))
  private def termValues(t: Checker.CheckedTerm): List[Checker.CheckedValue] =
    t.values.flatMap(values) ++ t.children.flatMap(termValues) ++ t.operations.flatMap(o => termValues(o.body))
  private def steps(w: Witness): List[Binding] = w match {
    case Witness.Refl(_) => Nil
    case Witness.Use(b) => List(b)
    case Witness.Compose(a, b) => steps(a) ++ steps(b)
  }

  for name <- names do test(s"$name: exact operation evidence, ATCs and nested execution") {
    val p = check(source(name))
    assertEquals(p.body.residuals, Nil)
    Core.elaborate(p)
    val ops = terms(p.body).filter(_.operationTyping.nonEmpty)
    assertEquals(ops.map(_.evidence.head.distance.concrete), List(Some(0), Some(1), Some(2)))
    val layerValue = if name == "mixed-answer-layers" then Type.Bool else Type.Int
    val hole = AnswerContext.Hole
    val inner = AnswerContext.Layer(layerValue, Computation(Type.Int), hole, None)
    val outer = AnswerContext.Layer(layerValue, Computation(Type.Int),
      AnswerContext.Layer(Type.Int, Computation(Type.Int), hole, None), None)
    assertEquals(ops.map(_.operationTyping.get.context), List(hole, inner, outer))
    val witnesses = ops.map(_.evidence.head)
    val callRegion = witnesses.head.constraint.from
    assert(witnesses.forall(_.constraint.from == callRegion))
    assertEquals(witnesses.map(_.constraint.to).distinct.size, 3)
    assertEquals(steps(witnesses(2)).take(1), steps(witnesses(1)))
    assertEquals(steps(witnesses(2)).map(_.distance), List(Distance.Numeral(1), Distance.Numeral(1)))
    assertEquals(AtmReference.run(p.source), "true")
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized), Right("true"))
  }

  test("nested-residuals: abstract helper evidence is instantiated at two caller depths") {
    val p = check(source("nested-residuals"))
    val ts = terms(p.body)
    val helper = ts.find(_.source match {
      case Term.Apply(Value.Variable("seed"), _) => true
      case _ => false
    }).get.evidence.head
    val path = steps(helper)
    assertEquals(path.size, 4)
    assertEquals(path.take(3).map(_.distance), List.fill(3)(Distance.Numeral(1)))
    assertEquals(path.last.distance.concrete, None)
    val abstraction = termValues(p.body).find(_.abstraction.exists(_.call == path.last)).get
    assert(abstraction.discharged.nonEmpty)
    assertEquals(abstraction.body.get.residuals, Nil)
    assertEquals(path.last.constraint.to, Evidence.Top)
    val calls = ts.filter(_.source match {
      case Term.Apply(Value.Variable("nested"), _) => true
      case _ => false
    })
    assertEquals(calls.map(_.evidence.head.distance.concrete), List(Some(0), Some(1)))
    assert(calls.forall(_.evidence.head.constraint.to == path.last.constraint.to))
  }

  test("shadowed-capabilities: aliases retain the original generative handler identities") {
    val ts = terms(check(source("shadowed-capabilities")).body)
    def alias(name: String): String = ts.collectFirst {
      case t if t.source.isInstanceOf[Term.Let] && t.source.asInstanceOf[Term.Let].name == name =>
        t.children.head.values.head.tpe.asInstanceOf[Type.Capability].name
    }.get
    val ops = ts.filter(_.operationTyping.nonEmpty)
    assertEquals(ops(1).evidence.head.constraint.to, alias("middle"))
    assertEquals(ops(2).evidence.head.constraint.to, alias("saved"))
  }

  test("a wrong answer at the ATC hole is rejected") {
    val s = source("active-three-counts").replace(
      "def ask(u: Unit): Int / (Int => Bool)", "def ask(u: Unit): Int / (Int => Int)")
    val result = Parser.parse(s).flatMap(Checker.check).swap.toOption.get
    assertEquals(result.kind, DiagnosticKind.TypeError)
    assert(result.message.contains("operation requires answer Int"), result.message)
  }

  test("two evidence steps cannot be justified by one answer layer") {
    val s = source("active-three-counts")
      .replace("        innermost.tick();\n        inner.tick();\n", "")
      .replace("with innermost: Int / (Int => Int / (Int => Bool))", "with innermost: Int / (Int => Bool)")
    val result = Parser.parse(s).flatMap(Checker.check).swap.toOption.get
    assertEquals(result.kind, DiagnosticKind.TypeError)
    assert(result.message.contains("too few concrete layers"), result.message)
  }

  test("polymorphic-outer-call reconstructs residual evidence but cannot infer a symbolic-depth ATC") {
    val parsed = Parser.parse(source("polymorphic-outer-call")).fold(e => fail(e.message), identity)
    val inspection = Checker.inspect(parsed)
    val error = inspection.result.swap.toOption.get
    assertEquals(error.kind, DiagnosticKind.Unsupported)
    assert(error.message.contains("concrete evidence distance"))
    assertEquals(inspection.operations.size, 1)
    val attempt = inspection.operations.head
    assertEquals(attempt.operation, "ask")
    assertEquals(attempt.evidence.residuals.size, 1)
    val obligation = attempt.evidence.residuals.head
    assertEquals(attempt.evidence.witness, Witness.Use(obligation.binding))
    assert(obligation.constraint.from.startsWith("bodyRegion#"))
    assertEquals(obligation.constraint.to, attempt.receiver)
    assertEquals(attempt.evidence.witness.distance.concrete, None)
    assertEquals(attempt.context.swap.toOption.get.kind, DiagnosticKind.Unsupported)
    assertEquals(Checker.check(parsed).swap.toOption.get.message, error.message)
  }

  test("inspection preserves successful typechecking results") {
    for name <- names do {
      val parsed = Parser.parse(source(name)).fold(e => fail(e.message), identity)
      val observed = Checker.inspect(parsed)
      val plain = Checker.check(parsed).fold(e => fail(e.message), identity)
      assertEquals(Core.show(Core.elaborate(observed.result.toOption.get)), Core.show(Core.elaborate(plain)))
      assertEquals(observed.operations.size, 3)
      assert(observed.operations.forall(_.context.isRight))
    }
  }
}

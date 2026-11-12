package effekt

import effekt.atm.{Annotations, Checker, Core, DirectBackend, Evidence, Parser, Syntax}
import Syntax.*
import java.nio.file.{Files, Path}

class AtmExplicitFileTests extends munit.FunSuite {
  private def source = Files.readString(Path.of("examples/atm/file-reusable.effekt"))
  private def checked(s: String) = Parser.parse(s).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def run(s: String, expected: String): Checker.CheckedProgram = {
    val p = checked(s)
    Core.elaborate(p)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
    p
  }
  private def reject(s: String, reason: String): Unit = {
    val p = Parser.parse(s).flatMap(Checker.check)
    assert(p.isLeft, "Accepted an invalid program")
    assert(p.swap.toOption.get.message.contains(reason), p.toString)
  }
  private def terms(t: Core.Term): List[Core.Term] = {
    def value(v: Core.Value): List[Core.Term] = v.node match {
      case Core.ValueNode.FixedLambda(_, _, b) => terms(b)
      case Core.ValueNode.Lambda(_, _, b) => terms(b)
      case Core.ValueNode.CapabilityLambda(_, _, _, b) => value(b)
      case Core.ValueNode.KindLambda(_, _, b) => value(b)
      case Core.ValueNode.TypeLambda(_, b) => value(b)
      case Core.ValueNode.Instantiate(f, c, _) => value(f) ++ value(c)
      case Core.ValueNode.InstantiateKind(f, _) => value(f)
      case Core.ValueNode.InstantiateType(f, _) => value(f)
      case Core.ValueNode.Primitive(_, args) => args.flatMap(value)
      case _ => Nil
    }
    t :: (t.node match {
      case Core.Node.Return(v) => value(v)
      case Core.Node.Let(_, a, b) => terms(a) ++ terms(b)
      case Core.Node.Apply(f, a, _, _) => value(f) ++ value(a)
      case Core.Node.Handle(_, _, b, _, r, ops) => terms(b) ++ terms(r) ++ ops.flatMap(o => terms(o.body))
      case Core.Node.If(c, a, b) => value(c) ++ terms(a) ++ terms(b)
      case Core.Node.Ascribe(b) => terms(b)
      case _ => Nil
    })
  }
  // Keep the entire definition but replace all uses with a pure result.
  private def unused(s: String): String = s.take(s.indexOf("  file[Int](7)(")) + "  0\n}\n"

  test("explicit reusable Fig. 1 has one polymorphic helper body and two instantiations") {
    val p = run(source, "42")
    val all = terms(Core.elaborate(p).body)
    val helpers = all.collect { case Core.Term(Core.Node.Let("file", binding, _), _, _) => binding }
    assertEquals(helpers.size, 1)
    assert(helpers.head.tpe.value match {
      case Type.KindPoly(_, Annotations.Kind.Computation, f: Type.Function) =>
        f.argument == Type.Int && f.result.value.isInstanceOf[Type.FixedFunction]
      case _ => false
    })
    assertEquals(all.count(_.node.isInstanceOf[Core.Node.Handle]), 1)
    assert(!terms(helpers.head).exists(_.node.isInstanceOf[Core.Node.Perform]))
    val instantiations = all.collect {
      case Core.Term(Core.Node.Apply(Core.Value(Core.ValueNode.InstantiateKind(
        Core.Value(Core.ValueNode.Variable("file"), _), Annotations.Argument.Computation(c)), _), _, _, _), _, _) => c
    }
    assertEquals(instantiations.size, 2)
    assertEquals(instantiations.count(_.effect == Effect.Pure), 1)
    assertEquals(instantiations.count(_.effect.isInstanceOf[Effect.Change]), 1)
    val ops = all.collect { case Core.Term(Core.Node.Perform(_, _, _, e, t), _, _) => (e.distance.concrete, t.context) }
    assertEquals(ops.map(_._1), List(None, None, None, None))
    assertEquals(ops.count(_._2.isInstanceOf[Evidence.AnswerContext.Variable]), 2)
  }
  test("the former uncurried caller-dependent callback domain is rejected") {
    val uncurried = unused(source)
      .replace("(c: Int)[R: Reg] {\n    fun (body:", "(c: Int, body:")
      .replace(") at [R]: C {", ")[R: Reg]: C {")
      .replace("\n    }\n  };", "\n  };")
    Parser.parse(uncurried).fold(e => fail(e.message), identity)
    reject(uncurried, "Unknown or out-of-scope capability R")
  }
  test("descriptor values remain runtime arguments") {
    run(source.replace("x + y", "x * 100 + y"), "735")
    run(source.replace("(7)(", "(11)(").replace("(35)(", "(31)("), "42")
  }
  test("helper and interface names have no special meaning") {
    run(source.replace("file", "usingResource").replace("body", "action").replace("File", "Resource"), "42")
  }
  test("a fully checked polymorphic helper need not be used") {
    run(unused(source), "0")
  }
  test("an unused polymorphic helper still checks its operation clauses") {
    reject(unused(source.replace("resume(c)", "resume(true)")), "Expected Int")
  }
  test("a helper is checked before its uses") {
    // Both the definition and the use are wrong: the definition must fail first.
    reject(source.replace("resume(c)", "missingValue").replace("(7)(", "(true)("), "Unknown value missingValue")
  }
  test("callback result annotations are checked") {
    reject(source.replace("x + y", "true"), "Expected Int")
  }
  test("helper argument annotations are checked") {
    reject(source.replace("(7)(", "(true)("), "Expected Int")
  }
  test("callback capabilities cannot escape in an Int result") {
    reject(source.replace("x + y", "b"), "Expected Int")
  }
  test("explicit evidence cannot erase a handler edge") {
    reject(source.replace("body{h}[step(h)][[]]()", "body{h}[refl][[]]()"), "Annotation kind mismatch")
  }
  test("an outer operation requires its one-layer explicit ATC") {
    reject(source.replace("[path(b) + EB + PA; J[layer(Int, K[Int], K)]]", "[path(b) + EB + PA; J]"), "ATC depth differs")
  }
  test("an operation cannot use evidence with reversed endpoints") {
    reject(source.replace("Ev(b, 1, S)", "Ev(S, 1, b)"), "Ill-composed region evidence")
  }
  test("an incorrect computation instantiation is rejected") {
    val s = source.replace("file[A]", "file[Bool]")
    assert(Parser.parse(s).flatMap(Checker.check).isLeft)
  }
  test("symbolic-distance ATC inference still requires an annotation") {
    reject(Files.readString(Path.of("examples/atm/infer/polymorphic-outer-call.effekt")), "concrete evidence distance")
  }
  test("native block declarations do not defer checking until use") {
    reject("""interface File { def read(): Int }
      def file(c: Int) {body: {File} => Int} = missingValue
      def main(): Int = 0
    """, "Native block inference is unsupported; use explicit")
  }
  test("the other explicitly polymorphic file example remains reusable") {
    run(Files.readString(Path.of("examples/atm/file-polymorphic.effekt")), "42")
  }
}

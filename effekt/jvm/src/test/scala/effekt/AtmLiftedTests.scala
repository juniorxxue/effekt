package effekt

import effekt.atm.{Checker, Core, DirectBackend, Evidence, LiftedLowering, LiftedValidation, Parser, Syntax}
import effekt.{lifted as L}
import java.nio.file.{Files, Path}

class AtmLiftedTests extends munit.FunSuite {
  private def check(s: String): Checker.CheckedProgram =
    Parser.parse(s).flatMap(Checker.check).fold(e => fail(e.message), identity)
  private def example(n: String): Checker.CheckedProgram = check(Files.readString(Path.of("examples/atm", n + ".effekt")))
  private def run(s: String, expected: String): Unit = {
    val p = check(s)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
  }
  private def validate(s: L.Stmt): Unit = {
    val main = effekt.core.Id("testMain")
    LiftedValidation.check(L.ModuleDecl("test", Nil, Nil, Nil,
      List(L.Definition.Def(main, L.Block.BlockLit(Nil, Nil, s))), List(main)))
  }

  test("ATM Lifted preserves distinct resumption, clause and handler result types") {
    val p = LiftedLowering(example("int-to-bool"))
    val h = p.annotations.map(_.statement).collectFirst { case h: L.Stmt.Handle => h }.get
    assert(LiftedValidation.same(h.body.body.tpe, L.Type.TInt))
    assert(LiftedValidation.same(h.onReturn.body.tpe, L.Type.TInt))
    assert(LiftedValidation.same(h.result, L.Type.TBoolean))
    val L.Stmt.Shift(_, resume) = h.handler.head.operations.head.implementation.body: @unchecked
    val L.Param.BlockParam(_, signature: L.BlockType.Function) = resume.params.head: @unchecked
    assert(LiftedValidation.same(signature.result, L.Type.TInt))
    assert(LiftedValidation.same(resume.body.tpe, L.Type.TBoolean))
    assertEquals(signature.eparams.size, 1)
    // Only the ordinary pair type is needed; answer variants are not generated.
    assertEquals(p.module.decls.count(_.isInstanceOf[L.Declaration.Data]), 1)
  }

  test("abstract call evidence and its captured handler prefix survive into Lifted") {
    val p = LiftedLowering(example("evidence-functions"))
    val calls = p.annotations.map(_.statement).collect { case L.Stmt.App(_, _, args) => args }
    val composed = calls.flatMap(_.collect { case e: L.Evidence if e.lifts.size == 2 => e }).head
    assert(composed.lifts.forall(_.isInstanceOf[L.Lift.Var]))
    assertEquals(composed.lifts.distinct.size, 2)
    assert(calls.exists(_.exists { case L.Evidence(Nil) => true; case _ => false }))
    assertEquals(DirectBackend.run(example("evidence-functions")), Right("42"))
  }

  test("every multi-shot resumption runs the nonidentity return clause") {
    run("""def main(): Bool = {
      try { h.ask() } with h: Bool {
        def ask(u: Unit): Int / (Int => Bool) = {
          val a = resume(1); val b = resume(2); a + b == 5
        }
        return x => { x + 1 }
      }
    }""", "true")
  }
  test("an outer operation crosses two delimiters with composed evidence and a depth-two ATC") {
    val p = example("evidence-two-delimiters")
    val target = LiftedLowering(p)
    assert(target.annotations.exists { a => a.statement match {
      case L.Stmt.App(_: L.Block.Member, _, List(L.Evidence(lifts), _)) => lifts.size == 2
      case _ => false
    }})
    assertEquals(AtmReference.run(p.source), "false")
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized), Right("false"))
  }
  test("an aborting operation bypasses the return clause") {
    run("""def main(): Bool = {
      try { h.ask() } with h: Bool {
        def ask(u: Unit): Int / (Int => Bool) = { true }
        return x => { x + 100 }
      }
    }""", "true")
  }
  test("normal return permits a different return-clause result type") {
    run("""def main(): Bool = {
      try { 42 } with h: Bool {
        def unused(u: Unit): Int / (Bool => Bool) = { resume(0) }
        return x => { x == 42 }
      }
    }""", "true")
  }
  test("function-installed handlers work beneath a caller-owned delimiter") {
    run("""def main(): Int = {
      def f(u: Unit): Int = {
        try { h.ask() } with h: Int {
          def ask(u: Unit): Int / (Int => Int) = { resume(42) }
          return x => { x }
        }
      }
      try { f() } with outer: Int {
        def unused(u: Unit): Int / (Int => Int) = { resume(0) }
        return x => { x }
      }
    }""", "42")
  }
  test("negative arbitrary-precision division and remainder agree with source arithmetic") {
    run("def main(): Int = { -9007199254740995 / 3 }", "-3002399751580331")
    run("def main(): Int = { -9007199254740995 % 3 }", "-2")
  }

  test("core rejects an ATC whose depth no longer matches its witness") {
    val root = Core.elaborate(example("int-to-bool")).body
    val h: Core.Node.Handle = root.node.asInstanceOf[Core.Node.Handle]
    val op = h.body.node.asInstanceOf[Core.Node.Perform]
    val context = Evidence.AnswerContext.Layer(Syntax.Type.Int, Syntax.Computation(Syntax.Type.Int), Evidence.AnswerContext.Hole, None)
    val forged = h.body.copy(node = op.copy(typing = op.typing.copy(context = context)))
    val error = intercept[Syntax.Diagnostic](Core.validate(root.copy(node = h.copy(body = forged))))
    assert(error.message.contains("ATC depth"))
  }
  test("core rejects an operation moved to a different region") {
    val root = Core.elaborate(example("int-to-bool")).body
    val h = root.node.asInstanceOf[Core.Node.Handle]
    val error = intercept[Syntax.Diagnostic](Core.validate(root.copy(node = h.copy(body = h.body.copy(region = Evidence.Top)))))
    assert(error.message.contains("different region"))
  }
  test("core checks operation receiver identity independently of witness depth") {
    val root = Core.elaborate(example("evidence-two-delimiters")).body
    val outer = root.node.asInstanceOf[Core.Node.Handle]
    val middle = outer.body.node.asInstanceOf[Core.Node.Handle]
    val inner = middle.body.node.asInstanceOf[Core.Node.Handle]
    val operation = inner.body.node.asInstanceOf[Core.Node.Perform]
    val forged = inner.body.copy(node = operation.copy(receiver = middle.region))
    val tree = root.copy(node = outer.copy(body = outer.body.copy(node = middle.copy(
      body = middle.body.copy(node = inner.copy(body = forged))))))
    val error = intercept[Syntax.Diagnostic](Core.validate(tree))
    assert(error.message.contains("targets a different capability"))
  }
  test("core rejects an unbound value before backend lowering") {
    val root = Core.elaborate(check("def main(): Int = { 42 }")).body
    val forged = root.copy(node = Core.Node.Return(Core.Value(Core.ValueNode.Variable("missing"), Syntax.Type.Int)))
    val error = intercept[Syntax.Diagnostic](Core.validate(forged))
    assert(error.message.contains("unbound value"))
  }
  test("core rejects a changed base type on a bound value") {
    val root = Core.elaborate(check("def main(): Int = { val x = 42; x }")).body
    val let = root.node.asInstanceOf[Core.Node.Let]
    val forged = let.body.copy(node = Core.Node.Return(Core.Value(Core.ValueNode.Variable("x"), Syntax.Type.Bool)))
    val error = intercept[Syntax.Diagnostic](Core.validate(root.copy(node = let.copy(body = forged))))
    assert(error.message.contains("variable type differs"))
  }
  test("Lifted rejects a free evidence argument") {
    val ev = new L.EvidenceSymbol()
    val f = L.Block.BlockLit(Nil, List(L.Param.EvidenceParam(new L.EvidenceSymbol())), L.Stmt.Return(L.Expr.Literal(BigInt(1), L.Type.TInt)))
    val error = intercept[Syntax.Diagnostic](validate(L.Stmt.App(f, Nil, List(L.Evidence(List(L.Lift.Var(ev)))))))
    assert(error.message.contains("unbound evidence"))
  }
  test("Lifted rejects missing evidence even when synthesized result types match") {
    val f = L.Block.BlockLit(Nil, List(L.Param.EvidenceParam(new L.EvidenceSymbol())), L.Stmt.Return(L.Expr.Literal(BigInt(1), L.Type.TInt)))
    val error = intercept[Syntax.Diagnostic](validate(L.Stmt.App(f, Nil, Nil)))
    assert(error.message.contains("argument arity"))
  }
  test("Lifted rejects an application with the wrong value argument type") {
    val arg = effekt.core.Id("arg")
    val f = L.Block.BlockLit(Nil, List(L.Param.ValueParam(arg, L.Type.TInt)), L.Stmt.Return(L.Expr.ValueVar(arg, L.Type.TInt)))
    val error = intercept[Syntax.Diagnostic](validate(L.Stmt.App(f, Nil, List(L.Expr.Literal(true, L.Type.TBoolean)))))
    assert(error.message.contains("value argument type"))
  }
  test("Lifted rejects a return clause with the wrong input type") {
    val p = LiftedLowering(example("int-to-bool"))
    val L.Definition.Def(id, main: L.Block.BlockLit) = p.module.definitions.head: @unchecked
    val h = main.body.asInstanceOf[L.Stmt.Handle]
    val ret: L.Block.BlockLit = L.Block.BlockLit(Nil, List(L.Param.ValueParam(effekt.core.Id("wrong"), L.Type.TBoolean)),
      L.Stmt.Return(L.Expr.Literal(BigInt(1), L.Type.TInt)))
    val forged = p.module.copy(definitions = List(L.Definition.Def(id, main.copy(body = h.copy(onReturn = ret)))))
    val error = intercept[Syntax.Diagnostic](LiftedValidation.check(forged))
    assert(error.message.contains("return clause input type"))
  }
}

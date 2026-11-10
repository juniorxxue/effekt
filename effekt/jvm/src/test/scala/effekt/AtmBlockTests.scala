package effekt

import effekt.atm.{Checker, Core, DirectBackend, Evidence, Parser, Syntax}
import Syntax.*
import java.nio.file.{Files, Path}

class AtmBlockTests extends munit.FunSuite {
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
    assert(p.isLeft)
    assert(p.swap.toOption.get.message.contains(reason), p.toString)
  }
  private def terms(t: Core.Term): List[Core.Term] = {
    def value(v: Core.Value): List[Core.Term] = v.node match {
      case Core.ValueNode.LocalLambda(_, _, b) => terms(b)
      case Core.ValueNode.Lambda(_, _, b) => terms(b)
      case Core.ValueNode.CapabilityLambda(_, _, _, b) => value(b)
      case Core.ValueNode.KindLambda(_, _, b) => value(b)
      case Core.ValueNode.Instantiate(f, c, _) => value(f) ++ value(c)
      case Core.ValueNode.InstantiateKind(f, _) => value(f)
      case Core.ValueNode.Primitive(_, args) => args.flatMap(value)
      case _ => Nil
    }
    t :: (t.node match {
      case Core.Node.Return(v) => value(v)
      case Core.Node.Let(_, a, b) => terms(a) ++ terms(b)
      case Core.Node.Apply(f, a, _) => value(f) ++ value(a)
      case Core.Node.Handle(_, _, b, _, r, ops) => terms(b) ++ terms(r) ++ ops.flatMap(o => terms(o.body))
      case Core.Node.If(c, a, b) => value(c) ++ terms(a) ++ terms(b)
      case Core.Node.Ascribe(b) => terms(b)
      case _ => Nil
    })
  }

  test("reusable Fig. 1 keeps runtime helpers and callbacks, with inferred concrete ATCs") {
    val p = run(source, "42")
    val core = Core.elaborate(p)
    val all = terms(core.body)
    val helpers = all.collect {
      case Core.Term(Core.Node.Let(n, Core.Term(Core.Node.Return(Core.Value(Core.ValueNode.LocalLambda(_, _, body), _)), _, _), call), _, _)
          if n.startsWith("fileSpecialization") =>
        assert(call.node match {
          case Core.Node.Apply(Core.Value(Core.ValueNode.Variable(m), _), _, Nil) => m == n
          case _ => false
        })
        // The handler invokes the block parameter; its callback's operations
        // have not been substituted into the helper function's body.
        assert(!terms(body).exists(_.node.isInstanceOf[Core.Node.Perform]))
        assert(terms(body).exists(_.node.isInstanceOf[Core.Node.Handle]))
        body
    }
    assertEquals(helpers.size, 2)
    val ops = all.collect { case Core.Term(Core.Node.Perform(_, _, _, e, t), _, _) => (e.distance.concrete, t.context) }
    assertEquals(ops.map(_._1), List(Some(1), Some(0), Some(1), Some(0)))
    assertEquals(ops.count(_._2 == Evidence.AnswerContext.Hole), 2)
  }
  test("each reusable helper invocation retains its own descriptor") {
    run(source.replace("x + y", "x * 100 + y"), "735")
    run(source.replace("file(7)", "file(11)").replace("file(35)", "file(31)"), "42")
  }
  test("resumption-return behavior matches native Effekt and the inlined example") {
    val traced = source
      .replace("resume(readInt(c))", "{ val n = resume(readInt(c)); n * 10 + (if (c == 7) 1 else 2) }")
      .replace("closeFile(c); resume(())", "closeFile(c); val n = resume(()); n * 10 + (if (c == 7) 3 else 4)")
    run(traced, "424231")
  }
  test("call-site shadowing does not change the helper's lexical captures") {
    run(source.replace("def main() =", "def main() = { def readInt(c: Int): Int = 1000\n") + "\n}", "42")
  }
  test("ordinary parameter values remain runtime arguments") {
    run(source.replace("def main() =", "def main() = { val outer = 7\nval inner = 35\n")
      .replace("file(7)", "file(outer)").replace("file(35)", "file(inner)") + "\n}", "42")
  }
  test("block and helper names are not special cases") {
    run(source.replace("file", "usingResource").replace("body", "action").replace("File", "Resource"), "42")
  }
  test("the block result is checked") {
    reject(source.replace("x + y", "true"), "Expected Int")
  }
  test("the helper argument is checked") {
    reject(source.replace("file(7)", "file(true)"), "Expected Int")
  }
  test("block declarations must agree with the supplied handler interface") {
    reject(source.replace("interface File {", "interface Other { def ask(): Bool }\ninterface File {")
      .replace("{body: {File}", "{body: {Other}"), "block interface Other")
  }
  test("a block may be invoked twice under the same handler contract") {
    val s = source.replace("try { body {f} }", "try { val first = body {f}; first + body {f} }")
    run(s, "168")
  }
  test("additional real handler delimiters are counted") {
    val s = source.replace("interface File {", "interface Empty {}\ninterface File {")
      .replace("    file(35)", "    try { file(35)")
      .replace("      x + y\n    }", "      x + y\n    } } with unused: Empty {}")
    run(s, "42")
  }
  test("handler clauses retain their normal type checks") {
    reject(source.replace("resume(readInt(c))", "resume(true)"), "Expected Int")
  }
  test("a capability cannot escape through the block result") {
    reject(source.replace("x + y", "b"), "Expected Int")
  }
  test("native block inference does not change ordinary symbolic-distance rejection") {
    val s = Files.readString(Path.of("examples/atm/infer/polymorphic-outer-call.effekt"))
    reject(s, "concrete evidence distance")
  }
  test("native block forwarding preserves a runtime parameter") {
    val wrapper = "def wrapper(c: Int) {action: {File} => Int} = file(c) {action}\n"
    run(source.replace("def main() =", wrapper + "def main() =").replace("file(7)", "wrapper(7)"), "42")
  }
  test("a helper can be called inside an ordinary region-polymorphic function") {
    run(source.replace("def main() =", "def example() =") + "\ndef main() = example()\n", "42")
  }
  test("contextual block answers are not fixed to Int") {
    run(source.replace("{body: {File} => Int}", "{body: {File} => Bool}").replace("x + y", "x + y == 42"), "true")
  }
  test("parameterized interfaces work in native block declarations") {
    val s = """interface Reader[A] { def read(): A }
      def provide(n: Int) {body: {Reader[Int]} => Int} =
        try { body {r} } with r: Reader[Int] { def read() = resume(n) }
      def main() = provide(42) { {reader} => reader.read() }
    """
    run(s, "42")
  }
  test("a mismatched forwarded block cannot change its interface") {
    val wrapper = "interface Other { def ask(): Bool }\n"
    val s = source.replace("interface File {", wrapper + "interface File {")
      .replace("def main() =", "def wrapper(c: Int) {action: {Other} => Int} = file(c) {action}\ndef main() =")
      .replace("file(7)", "wrapper(7)")
    reject(s, "Forwarded block declarations disagree")
  }

  test("a value shadows a native block parameter") {
    reject(source.replace("try { body {f} }", "try { val body = 0; body {f} }"), "Unknown native block body")
  }
  test("a capability shadows a native block parameter") {
    reject(source.replace("try { body {f} } with f:", "try { body {body} } with body:"), "Unknown native block body")
  }

  test("a native block parameter shadows an older value even in value position") {
    val s = source.replace("def file(c:", "val body = 99\ndef file(c:")
      .replace("try { body {f} }", "try { val n = body {f}; body }")
    reject(s, "Unknown value body")
  }

}

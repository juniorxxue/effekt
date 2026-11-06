package effekt

import effekt.atm.{Checker, Core, Parser, Syntax, Logic, LiftedLowering}

class AtmDatatypeTests extends munit.FunSuite {
  private val option = "data OptionInt { None(); Some(value: Int) }\n"
  private def check(s: String) = Parser.parse(s).flatMap(Checker.check)
  private def accepted(s: String) = check(s).fold(e => fail(e.message), identity)
  private def rejected(s: String, reason: String): Unit = {
    val p = Parser.parse(s).fold(e => fail(e.message), identity)
    val result = Checker.check(p)
    assert(result.isLeft, "Incorrect datatype program accepted")
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
  }

  test("constructor refinements preserve a universally quantified field") {
    accepted(option + """def main(): OptionInt = {
      def wrap(n: Int): {o: OptionInt | o == Some(n)} = { Some(n) }
      wrap(42)
    }""")
  }
  test("SMT constructors are disjoint and injective") {
    accepted(option + """def main(): {b: Bool | b &&
      (forall x: Int. Some(x) != None()) &&
      (forall x: Int. forall y: Int. (Some(x) == Some(y)) => x == y)} = { true }""")
  }
  test("false constructor equality is rejected") {
    rejected(option + "def main(): {o: OptionInt | o == Some(0)} = { None() }", "Expected")
  }
  test("false field equality is rejected") {
    rejected(option + "def main(): {o: OptionInt | o == Some(0)} = { Some(1) }", "Expected")
  }
  test("constructor arity is checked") {
    rejected(option + "def main(): OptionInt = { Some() }", "Wrong number")
  }
  test("constructor fields are checked") {
    rejected(option + "def main(): OptionInt = { Some(true) }", "Expected Int")
  }
  test("logical constructor fields are sorted") {
    rejected(option + "def main(): {o: OptionInt | o == Some(true)} = { None() }", "must have sort Int")
  }
  test("logical constructors have checked arity") {
    rejected(option + "def main(): {o: OptionInt | o == Some()} = { None() }", "Wrong number")
  }
  test("constructor names cannot be ambiguous") {
    rejected(option + "data Other { None() } def main(): Int = { 0 }", "Duplicate constructor")
  }
  test("datatype names cannot be duplicated") {
    rejected(option + "data OptionInt { Another() } def main(): Int = { 0 }", "Duplicate datatype")
  }
  test("datatype fields cannot silently become nominal tokens") {
    rejected("data Bad { Mk(value: Missing) } def main(): Int = { 0 }", "closed pure")
  }
  test("effectful datatype fields require an explicit supported representation") {
    rejected("data Bad { Mk(f: ((Int) => Int / (Int => Bool))) } def main(): Int = { 0 }", "closed pure")
  }
  test("uninhabited recursive datatype cycles are rejected before SMT") {
    rejected("data Bad { Loop(next: Bad) } def main(): Int = { 0 }", "finite inhabitants")
  }
  test("token syntax cannot bypass a declared datatype") {
    rejected(option + "def main(): OptionInt = { token(OptionInt) }", "must be constructed")
  }
  test("recursive data and pairs have a mutually scoped SMT declaration") {
    accepted("""data Tree { Leaf(); Node(children: (Tree, Tree)) }
      def main(): {t: Tree | t != Leaf()} = { Node((Leaf(), Leaf())) }""")
  }
  test("mutually recursive types with a finite constructor are accepted") {
    accepted("""data A { Stop(); Next(b: B) } data B { Back(a: A) }
      def main(): {a: A | a != Stop()} = { Next(Back(Stop())) }""")
  }
  for (label, source, expected) <- List(
    ("option equality", option + "def main(): Bool = { (Some(-42) == Some(-42)) && (None() != Some(0)) && (Some(1) != Some(2)) }", "true"),
    ("recursive equality", "data ListInt { Nil(); Cons(head: Int, tail: ListInt) } def main(): Bool = { Cons(1, Cons(2, Nil())) == Cons(1, Cons(2, Nil())) }", "true"),
    ("pair field equality", "data Box { Wrap(pair: (Int, Bool)) } def main(): Bool = { (Wrap((1, true)) != Wrap((2, true))) && (Wrap((1, true)) == Wrap((1, true))) }", "true"),
    ("unit field", "data Box { Wrap(value: Unit) } def main(): {b: Bool | b} = { Wrap(()) == Wrap(()) }", "true")
  ) do test(s"$label executes through source, direct Lifted and legacy JS") {
    val p = accepted(source)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(AtmBackend.run(p, optimized).fold(fail(_), identity), expected)
  }
  test("Core rejects forged constructor fields independently of source inference") {
    val core = Core.elaborate(accepted(option + "def main(): OptionInt = { Some(1) }"))
    val bad = Core.Value(Core.ValueNode.Constructor("Some", List(Core.Value(Core.ValueNode.Boolean(true), Syntax.Type.Bool))), Syntax.Type.Data("OptionInt"))
    val body = core.body.copy(node = Core.Node.Return(bad))
    val error = intercept[Syntax.Diagnostic](Core.validate(body, core.datatypes))
    assert(error.message.contains("constructor field type mismatch"))
    LiftedLowering(core).validate()
  }
  test("datatype proof failures retain solver-unavailable diagnostics") {
    val p = Parser.parse(option + "def main(): {o: OptionInt | o != None()} = { Some(1) }").toOption.get
    val solver = new Logic.Solver { def prove(c: Logic.Context, e: Logic.Expr) = Logic.Verdict.Unknown("test solver unavailable") }
    val result = Checker.check(p, solver)
    assert(result.swap.toOption.get.message.contains("verification unavailable"))
  }
}

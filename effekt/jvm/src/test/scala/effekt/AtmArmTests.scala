package effekt

import effekt.atm.{Checker, Lowering, Parser}
import java.nio.file.{Files, Path}

class AtmArmTests extends munit.FunSuite {
  private val originalFamilies = Set("expectation", "choose-sum", "choose-max", "safe-div-1", "safe-div-2", "state-easy")
  private val expandedPorts = {
    val paths = Files.list(Path.of("examples/atm/arm"))
    try paths.toArray.map(_.asInstanceOf[Path]).filter { p =>
      p.toString.endsWith(".effekt") && !originalFamilies.exists(n => p.getFileName.toString.startsWith(n + "-"))
    }.toList.sortBy(_.toString)
    finally paths.close()
  }
  assert(expandedPorts.size == 41)
  for path <- expandedPorts do {
    val name = path.getFileName.toString
    val accepted = !name.endsWith("-UNSAT.effekt") || name == "distribution-UNSAT.effekt"
    test(s"expanded ARM $name: universal contract and runtime/classified rejection") {
      val parsed = Parser.parse(Files.readString(path)).fold(e => fail(e.message), identity)
      val result = Checker.check(parsed)
      if accepted then {
        val checked = result.fold(e => fail(e.message), identity)
        val expected = if name == "state-SAT.effekt" then "8" else "true"
        assertEquals(AtmReference.run(checked.source), expected)
        for optimized <- List(false, true) do
          assertEquals(AtmBackend.run(checked, optimized).fold(fail(_), identity), expected)
      } else {
        assert(result.isLeft, "False universal upstream contract accepted")
        assertEquals(result.swap.toOption.get.kind, effekt.atm.Syntax.DiagnosticKind.ContractFailure)
      }
    }
  }
  test("distribution: higher-order traversal preserves recursive scaling and duplicate insertion") {
    val original = Files.readString(Path.of("examples/atm/arm/distribution-SAT.effekt"))
    val witness = """  val withA = test(25); val withB = withA(3); val result = withB(7);
  result == Entry((7,75),Entry((3,25),Empty()))"""
    val source = original.replace(witness, """  val scaleInput = scaleList(Entry((1,60),Entry((2,40),Empty())));
  val scaled = scaleInput(50);
  val combine = mergeList(scaled);
  val result = combine(Entry((1,10),Entry((3,20),Empty())));
  val scaleEmpty = scaleList(Empty()); val scaledEmpty = scaleEmpty(50);
  val combineEmpty = mergeList(Empty()); val mergedEmpty = combineEmpty(Empty());
  result == Entry((1,40),Entry((3,20),Entry((2,20),Empty()))) &&
    scaledEmpty == Empty() && mergedEmpty == Empty()""")
    assert(source != original)
    val checked = Parser.parse(source).flatMap(Checker.check).fold(e => fail(e.message), identity)
    assertEquals(AtmReference.run(checked.source), "true")
    for optimized <- List(false, true) do
      assertEquals(AtmBackend.run(checked, optimized).fold(fail(_), identity), "true")
  }
  for (name, before, after, expected) <- List(
    ("bfs-SAT", "test(3)", "test(2)", "true"),
    ("distribution-SAT", "withB(7)", "withB(3)", "true"),
    ("state-SAT", "test(8)", "test(0)", "0"),
    ("yield-SAT", "Node(Leaf(0),Node(Leaf(0),Leaf(0)))", "Leaf(0)", "true")
  ) do test(s"$name: alternate execution path") {
    var source = Files.readString(Path.of("examples/atm/arm", name + ".effekt"))
    assert(source.contains(before)); source = source.replace(before, after)
    if name == "distribution-SAT" then source = source.replace("Entry((7,75),Entry((3,25),Empty()))", "Entry((3,100),Empty())")
    if name == "yield-SAT" then source = source.replace("Node(Leaf(1),Node(Leaf(1),Leaf(1)))", "Leaf(1)")
    val checked = Parser.parse(source).flatMap(Checker.check).fold(e => fail(e.message), identity)
    assertEquals(AtmReference.run(checked.source), expected)
    assertEquals(AtmBackend.run(checked, true).fold(fail(_), identity), expected)
  }
  for (name, expected) <- List("expectation" -> "600", "choose-sum" -> "0", "choose-max" -> "2", "safe-div-1" -> "true", "safe-div-2" -> "true", "state-easy" -> "8") do {
    test(s"$name: universal refinement checking and optimized/unoptimized execution") {
      val source = Files.readString(Path.of("examples/atm/arm", s"$name-SAT.effekt"))
      val checked = Parser.parse(source).flatMap(Checker.check).fold(e => fail(e.message), identity)
      assertEquals(AtmReference.run(checked.source), expected)
      for optimized <- List(false, true) do
        assertEquals(AtmBackend.run(checked, optimized).fold(fail(_), identity), expected)
    }
    if name != "state-easy" then test(s"$name: false upstream postcondition is rejected before lowering") {
      val source = Files.readString(Path.of("examples/atm/arm", s"$name-UNSAT.effekt"))
      val parsed = Parser.parse(source).fold(e => fail(e.message), identity)
      val checked = Checker.check(parsed)
      assert(checked.isLeft, "False universally quantified benchmark postcondition accepted")
      assert(checked.swap.toOption.get.message.contains("Expected"), checked.toString)
    }
  }
  test("state-easy: the stronger false result contract is still rejected") {
    val original = Files.readString(Path.of("examples/atm/arm/state-easy-SAT.effekt"))
    val bad = original.replace("def test(init: {n: Int | n >= 0})[R: Reg]: {r: Int | r >= 0}",
      "def test(init: {n: Int | n >= 0})[R: Reg]: {r: Int | r >= 1}")
    assert(bad != original)
    val parsed = Parser.parse(bad).fold(e => fail(e.message), identity)
    val result = Checker.check(parsed)
    assert(result.isLeft)
    assertEquals(result.swap.toOption.get.kind, effekt.atm.Syntax.DiagnosticKind.ContractFailure)
  }
  test("local recursion retains the original universal state property") {
    def checkState(source: String) = Parser.parse(source).flatMap(Checker.check).fold(e => fail(e.message), identity)
    val p = checkState(Files.readString(Path.of("examples/atm/arm/state-easy-SAT.effekt")))
    val original = Files.readString(Path.of("docs/atm/history/rejected-before-local/state-easy-SAT.atm.txt"))
    assertEquals(AtmReference.run(Parser.parse(original).toOption.get), AtmReference.run(p.source))
    for initial <- List(0, 1, 17) do {
      val q = checkState(Files.readString(Path.of("examples/atm/arm/state-easy-SAT.effekt")).replace("test(8)", s"test($initial)"))
      assertEquals(effekt.atm.DirectBackend.run(q).fold(fail(_), identity), initial.toString)
    }
  }
}

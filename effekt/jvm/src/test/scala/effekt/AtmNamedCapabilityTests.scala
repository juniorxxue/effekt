package effekt

import effekt.atm.{Checker, Core, DirectBackend, Evidence, Parser, Syntax}
import Syntax.*

class AtmNamedCapabilityTests extends munit.FunSuite {
  private def run(source: String, expected: String = "42"): Checker.CheckedProgram = {
    val p = Parser.parse(source).flatMap(Checker.check).fold(e => fail(e.message), identity)
    Core.elaborate(p)
    assertEquals(AtmReference.run(p.source), expected)
    for optimized <- List(false, true) do
      assertEquals(DirectBackend.run(p, optimized).fold(fail(_), identity), expected)
    p
  }
  private def reject(source: String, reason: String): Unit = {
    val result = Parser.parse(source).flatMap(Checker.check)
    assert(result.isLeft, "Accepted an invalid named capability parameter")
    assert(result.swap.toOption.get.message.contains(reason), result.toString)
  }
  private val ask = "interface Ask { def ask(): Int }\n"
  private val use = ask + """def use(body: (c: Ask) => ((Unit) =>[c] Int / (Int => Int))) at [top]: Int = {
    try { body(h)() } with h: Ask { def ask() = resume(42) }: Int
  }
  """
  private val callback = "fun (a: Ask) { fun () at [a]: Int / (Int => Int) { a.ask() } }"

  test("a named interface supplies callback contracts from the consumer result") {
    run(use + s"def main(): Int = use($callback)")
  }
  test("ordinary caller-indexed consumers also resolve named capability domains") {
    run((use + s"def main(): Int = use($callback)").replace(" at [top]", ""))
  }
  test("recursive consumers resolve their callback domain before checking the body") {
    run(use.replace("try { body(h)() }", "if (false) { use(body) } else try { body(h)() }") +
      s"def main(): Int = use($callback)")
  }
  test("an expected domain supplies its answers even when the consumer returns a different type") {
    run(ask + """def main(): Int = {
      val consume: ((c: { def ask(): Int / (Bool => Bool); }) => cap(c)) =>[top] Int =
        fun (body: (c: Ask) => cap(c)) at [top]: Int { 42 };
      consume(fun (a: Ask) { a })
    }""")
  }
  test("expected domain contracts do not import the consumer's caller-dependent result") {
    run(ask + """def main(): Int = {
      val consume: ((c: { def ask(): Int / (Bool => Bool); }) => cap(c))[R: Reg] => ((Unit) =>[R] Int) =
        fun (body: (c: Ask) => cap(c))[S: Reg]: ((Unit) =>[S] Int) { fun () at [S]: Int { 42 } };
      consume(fun (a: Ask) { a })()
    }""")
  }
  test("named capabilities can be forwarded as values") {
    run(ask + """def use(body: (c: Ask) => cap(c)) at [top]: Int = {
      try { val saved = body(h); saved.ask() } with h: Ask { def ask() = resume(42) }: Int
    }
    def main(): Int = use(fun (a: Ask) { a })""")
  }
  test("a capability application inside a named abstraction remains a value") {
    run(ask + """def use(body: (c: Ask) => cap(c)) at [top]: Int = {
      try { val saved = body(h); saved.ask() } with h: Ask { def ask() = resume(42) }: Int
    }
    def main(): Int = {
      val forward = fun (c: { def ask(): Int / (Int => Int); }) { c };
      use(fun (a: Ask) { forward(a) })
    }""")
  }
  test("named abstractions can be called from structural value abstractions") {
    run(ask + """def main(): Int = {
      val forward: (c: { def ask(): Int / (Int => Int); }) => cap(c) = fun (a: Ask) { a };
      val again = fun (d: { def ask(): Int / (Int => Int); }) { forward(d) };
      try { val saved = again(h); saved.ask() } with h: Ask { def ask() = resume(42) }: Int
    }""")
  }
  private val reader = """interface Reader[A] { def read(): A }
    val use = fun [A](x: A) {
      fun (body: (r: Reader[A]) => ((Unit) =>[r] A / (A => A))) at [top]: A {
        try { body(h)() } with h: Reader[A] { def read() = resume(x) }: A
      }
    }
    def main(): Int = use[Int](42)(fun (r: Reader[Int]) {
      fun () at [r]: Int / (Int => Int) { r.read() }
    })"""
  test("parameterized interface schemas are instantiated before checking the callback") {
    run(reader)
  }
  test("named parameters check interface type arguments and their arity") {
    reject(reader.replace("fun (r: Reader[Int])", "fun (r: Reader[Bool])"), "expected interface operation signatures")
    reject(reader.replace("r: Reader[A]", "r: Reader"), "Wrong number of type arguments for interface Reader")
  }
  test("interface operations keep their declaration scope under local aliases") {
    run("""type Number = Int
      interface Read { def read(): Number }
      def use(body: (c: Read) => ((Unit) =>[c] Int / (Int => Int))) at [top]: Int = {
        try { body(h)() } with h: Read { def read() = resume(42) }: Int
      }
      def main(): Int = {
        type Number = Bool;
        use(fun (c: Read) { fun () at [c]: Int / (Int => Int) { c.read() } })
      }""")
  }
  test("ordinary type parameters can shadow interface names") {
    run(ask + """def identity[Ask](x: Ask): Ask = x
      def main(): Int = identity[Int](42)""")
  }
  test("nested type binders do not trigger interface answer elaboration") {
    run(ask + """def main(): Int = {
      val consume = fun (f: forall type Ask. ((Ask) => Ask))[R: Reg]: ((Unit) =>[R] Int) {
        fun () at [R]: Int { 42 }
      };
      consume(fun [A](x: A): A { x })()
    }""")
  }
  test("local value-type aliases can shadow interface names") {
    run(ask + """def main(): Int = {
      type Ask = Int;
      val identity = fun (x: Ask): Ask { x };
      identity(42)
    }""")
  }
  test("expected structural contracts retain answer-changing endpoints") {
    run(ask + """def main(): Bool = {
      val call: (c: { def ask(): Int / (Int => Bool); }) => ((Unit) =>[c] Int / (Int => Bool)) =
        fun (a: Ask) { fun () at [a]: Int / (Int => Bool) { a.ask() } };
      try { call(h)() } with h: Ask {
        def ask() / (Int => Bool) = { val n = resume(42); n == 42 }
      }: Bool
    }""", "true")
  }
  test("dependent operation value signatures are matched modulo their binders") {
    run("""interface Id { def id(x: Int): { y: Int | y == x } }
      def main(): Int = {
        val call: (c: { def id(n: Int): { z: Int | z == n } / (Int => Int); }) =>
          ((Unit) =>[c] Int / (Int => Int)) =
          fun (a: Id) { fun () at [a]: Int / (Int => Int) { a.id(42) } };
        try { call(h)() } with h: Id { def id(n) = resume(n) }: Int
      }""")
  }
  test("a mismatched interface is rejected even if the callback does not call it") {
    reject("interface Other { def other(): Int }\n" + use +
      "def main(): Int = use(fun (a: Other) { fun () at [a]: Int / (Int => Int) { 42 } })",
      "expected interface operation signatures")
  }
  test("named contracts still reject incompatible handler answers") {
    reject(use.replace("def ask() = resume(42)", "def ask() / (Bool => Int) = 42") +
      s"def main(): Int = use($callback)", "different operation or answer contract")
  }
  test("unknown operations on a named parameter are rejected") {
    reject(use + s"def main(): Int = use(${callback.replace("a.ask()", "a.missing()")})", "Unknown operation")
  }
  test("an unused consumer is checked before any call site") {
    reject(use.replace("resume(42)", "resume(true)") + "def main(): Int = 0", "Expected Int")
  }
  test("named capability parameters retain handler escape checking") {
    reject(ask + """def use(body: (c: Ask) => cap(c)) at [top]: Int = {
      try { body(h) } with h: Ask { def ask() = resume(42) return ignored => 42 }: Int
    }
    def main(): Int = 0""", "local handler region")
  }
  test("an uncontextualized interface parameter does not guess its ATM answers") {
    reject(ask + "def main(): Int = { val forward = fun (c: Ask) { c }; 42 }", "needs an expected capability contract")
    reject(ask + "def main(): Int = { val forward: (c: Ask) => cap(c) = fun (c: Ask) { c }; 42 }",
      "needs an expected capability contract")
  }
  test("empty interfaces need no operation answer contracts") {
    run("""interface Empty {}
      def main(): Int = {
        val forward: (c: Empty) => cap(c) = fun (e: Empty) { e };
        try { val saved = forward(h); 42 } with h: Empty {}: Int
      }""")
  }
  test("Core rejects unresolved named capability types") {
    val tpe = Type.Interface("Ask", Nil)
    val forged = Core.Term(Core.Node.Return(Core.Value(Core.ValueNode.Unit, tpe)), Computation(tpe), Evidence.Top)
    val error = intercept[Diagnostic] { Core.validate(forged) }
    assert(error.message.contains("unresolved interface capability type"))
  }
}

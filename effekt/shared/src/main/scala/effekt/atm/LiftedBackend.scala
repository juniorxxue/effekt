package effekt.atm

import effekt.generator.chez
import effekt.util.messages.{ErrorReporter, DebugMessaging}

/** The historical evidence-preserving Chez backend, fed directly with Lifted. */
object LiftedBackend {
  def emit(program: LiftedLowering.Program, optimized: Boolean = true): String = {
    program.validate()
    given ErrorReporter with {
      var focus: effekt.source.Tree = effekt.source.NoSource
      val messaging = new DebugMessaging
      val positions = new kiama.util.Positions
    }
    val definitions = chez.TransformerLift.toChez(program.module)
    val result = chez.ChezName("atm_result")
    val evaluated = chez.Expr.Variable(result)
    def display(t: Syntax.Type): chez.Expr = t match {
      case Syntax.Type.Refined(_, base, _) => display(base)
      case Syntax.Type.Int => evaluated
      case Syntax.Type.Bool => chez.Expr.If(evaluated, chez.ChezString("true"), chez.ChezString("false"))
      case Syntax.Type.Unit => chez.ChezString("unit")
      case Syntax.Type.Token(n) => chez.ChezString(n)
      case Syntax.Type.Data(_) => chez.ChezString("<data>")
      case Syntax.Type.Pair(_, _) => chez.ChezString("<pair>")
      case Syntax.Type.Capability(_) => chez.ChezString("<capability>")
      case _ => chez.ChezString("<function>")
    }
    val printed = chez.Expr.Let(List(chez.Binding(result, chez.TransformerLift.runMain(chez.nameRef(program.main)))),
      chez.Block(Nil, List(chez.Builtin("display", display(program.core.body.tpe.value))), chez.Builtin("newline")))
    val expression = chez.Expr.Let(Nil, chez.Block(definitions, Nil, printed))
    val output = if optimized then chez.cleanup(expression) else expression
    chez.PrettyPrinter.pretty(chez.PrettyPrinter.toDoc(output)).layout + "\n"
  }
}

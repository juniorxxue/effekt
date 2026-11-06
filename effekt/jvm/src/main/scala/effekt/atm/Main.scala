package effekt.atm

import java.nio.file.{Files, Path}

/** Experimental entry point; it does not alter the normal Effekt CLI. */
object Main {
  private def quote(s: String): String = "\"" + s.flatMap {
    case '"' => "\\\""
    case '\\' => "\\\\"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c => c.toString
  } + "\""

  def main(args: Array[String]): Unit = {
    val emitting = args.headOption.exists(Set("--emit", "--emit-effekt"))
    if args.length != (if emitting then 3 else 2) ||
       !Set("--check", "--check-json", "--elaborate", "--lifted", "--emit", "--emit-effekt", "--run").contains(args(0)) then {
      System.err.println("Usage: effekt.atm.Main --check INPUT | --check-json INPUT | --elaborate INPUT | --lifted INPUT | --emit INPUT OUTPUT.ss | --emit-effekt INPUT OUTPUT.effekt | --run INPUT")
      sys.exit(2)
    }
    val started = System.nanoTime()
    val checked = for {
      parsed <- Parser.parse(Files.readString(Path.of(args(1))))
      checked <- Checker.check(parsed)
    } yield checked
    if args(0) == "--check-json" then {
      val elapsed = (System.nanoTime() - started) / 1000000.0
      val fields = checked match {
        case Right(p) => s"\"status\":\"accepted\",\"type\":${quote(Syntax.show(p.body.tpe))}"
        case Left(e) => s"\"status\":\"rejected\",\"kind\":${quote(e.kind.toString)},\"message\":${quote(e.message)}"
      }
      println(s"{\"file\":${quote(args(1))},\"checking_ms\":$elapsed,$fields}")
      if checked.isLeft then sys.exit(1) else return
    }
    checked match {
      case Left(error) => System.err.println(s"ATM error: ${error.message}"); sys.exit(1)
      case Right(program) => try { args(0) match {
        case "--check" => println(Syntax.show(program.body.tpe))
        case "--elaborate" => println(Core.show(Core.elaborate(program)))
        case "--lifted" => println(LiftedLowering(program).show)
        case "--emit" => Files.writeString(Path.of(args(2)), DirectBackend.emit(program)); ()
        case "--emit-effekt" => Files.writeString(Path.of(args(2)), Lowering(program)); ()
        case "--run" =>
          DirectBackend.run(program) match {
            case Right(output) => println(output)
            case Left(error) => throw Syntax.Diagnostic(error)
          }
        case _ => sys.exit(2)
      }} catch { case e: Syntax.Diagnostic => System.err.println(s"ATM error: ${e.message}"); sys.exit(1) }
    }
  }
}

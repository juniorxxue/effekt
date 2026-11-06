package effekt.atm

import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal

/** Fail closed on missing solver, timeout, malformed output, or unknown. */
object RefinementSolver {
  val default: Logic.Solver = z3(sys.env.getOrElse("EFFEKT_ATM_Z3", "z3"))

  def z3(executable: String): Logic.Solver = new Logic.Solver {
    def prove(context: Logic.Context, conclusion: Logic.Expr): Logic.Verdict = {
      val script = Logic.query(context, conclusion)
      var process: Option[Process] = None
      try {
        val p = new ProcessBuilder(executable, "-in", "-smt2").redirectErrorStream(true).start()
        process = Some(p)
        val input = p.getOutputStream
        input.write(script.getBytes(StandardCharsets.UTF_8)); input.close()
        if !p.waitFor(5, TimeUnit.SECONDS) then {
          p.destroyForcibly(); Logic.Verdict.Unknown("Z3 timed out")
        } else {
          val output = new String(p.getInputStream.readNBytes(16384), StandardCharsets.UTF_8).trim
          if p.exitValue() != 0 then Logic.Verdict.Unknown(s"Z3 failed: $output")
          else output match {
            case "unsat" => Logic.Verdict.Valid
            case "sat" => Logic.Verdict.Invalid
            case "unknown" => Logic.Verdict.Unknown("Z3 returned unknown")
            case other => Logic.Verdict.Unknown(s"Unexpected Z3 output: $other")
          }
        }
      } catch {
        case NonFatal(e) => Logic.Verdict.Unknown(s"Cannot run Z3: ${e.getMessage}")
      } finally process.foreach { p =>
        p.getInputStream.close(); p.getOutputStream.close(); p.getErrorStream.close()
        if p.isAlive then p.destroyForcibly()
      }
    }
  }
}

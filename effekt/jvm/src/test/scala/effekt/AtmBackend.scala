package effekt

import effekt.atm.{Checker, Lowering, DirectBackend}
import java.nio.file.Files

/** Serialize in-process compiler checks across the ATM suites: the compiler's
 * task dependency tracing uses shared mutable state.
 */
object AtmBackend {
  def run(program: Checker.CheckedProgram, optimized: Boolean): Either[String, String] = synchronized {
    for {
      direct <- DirectBackend.run(program, optimized)
      legacy <- runLegacy(program, optimized)
      result <- if direct == legacy then Right(direct)
                else Left(s"Backend disagreement: direct Chez-Lift = $direct; legacy Effekt/JS = $legacy")
    } yield result
  }

  private def runLegacy(program: Checker.CheckedProgram, optimized: Boolean): Either[String, String] = {
    val directory = Files.createTempDirectory("effekt-atm-test-")
    val file = directory.resolve("program.effekt")
    val generated = Lowering(program)
    Files.writeString(file, generated)
    val driver = new effekt.Driver {}
    val options = Seq("--Koutput", "string", "--no-exit-on-error", "--backend", "js", "--out", directory.toString) ++
      (if optimized then Nil else List("--no-optimize"))
    val config = driver.createConfig(options)
    config.verify()
    driver.compileFile(file.toString, config)
    val output = config.stringEmitter.result()
    if driver.context.messaging.hasErrors then Left(s"Generated Effekt failed:\n$output\n$generated")
    else Right(output.trim)
  }
}

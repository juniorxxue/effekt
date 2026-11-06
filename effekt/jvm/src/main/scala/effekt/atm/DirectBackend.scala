package effekt.atm

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal

object DirectBackend {
  private def runtime: String = {
    val path = Path.of(sys.env.getOrElse("EFFEKT_ATM_RUNTIME", "libraries/chez/lift/effekt.ss"))
    if Files.isRegularFile(path) then Files.readString(path)
    else {
      val stream = Option(getClass.getClassLoader.getResourceAsStream("chez/lift/effekt.ss"))
        .getOrElse(throw Syntax.Diagnostic("Cannot find the historical Chez-Lift runtime; set EFFEKT_ATM_RUNTIME to libraries/chez/lift/effekt.ss"))
      try new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      finally stream.close()
    }
  }

  def emit(program: Checker.CheckedProgram, optimized: Boolean = true): String =
    "(import (chezscheme))\n" + runtime + "\n" + LiftedBackend.emit(LiftedLowering(program), optimized)

  def run(program: Checker.CheckedProgram, optimized: Boolean = true): Either[String, String] = {
    val directory = Files.createTempDirectory("effekt-atm-lifted-")
    val input = directory.resolve("program.ss")
    val output = directory.resolve("output.txt")
    try {
      Files.writeString(input, emit(program, optimized))
      val executable = sys.env.getOrElse("EFFEKT_ATM_CHEZ", "chez")
      val process = new ProcessBuilder(executable, "--script", input.toString)
        .redirectErrorStream(true).redirectOutput(output.toFile).start()
      if !process.waitFor(30, TimeUnit.SECONDS) then {
        process.destroyForcibly().waitFor()
        Left("Chez-Lift execution exceeded 30 seconds")
      } else {
        val result = Files.readString(output).trim
        if process.exitValue() == 0 then Right(result)
        else Left(s"Chez-Lift execution failed (${process.exitValue()}):\n$result")
      }
    } catch {
      case e: Syntax.Diagnostic => Left(e.message)
      case NonFatal(e) => Left(s"Direct Chez-Lift backend: ${e.getMessage}; set EFFEKT_ATM_CHEZ to the Chez executable if needed")
    } finally {
      Files.deleteIfExists(input)
      Files.deleteIfExists(output)
      Files.deleteIfExists(directory)
    }
  }
}

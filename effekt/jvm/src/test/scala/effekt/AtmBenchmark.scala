package effekt

import effekt.atm.{Checker, Core, Evidence, Parser, Syntax}
import java.nio.file.{Files, Path}

/** Reproducible paper-table measurements, separate from the compiler CLI.
 * Inspection never measures time. Timing parses once, creates a fresh checker
 * session for each run, and excludes Core inspection and backend execution.
 */
object AtmBenchmark {
  case class Certificates(evidence: Int = 0, atcs: Int = 0, layeredAtcs: Int = 0,
                          distances: List[Option[Int]] = Nil, contexts: List[Evidence.AnswerContext] = Nil) {
    def +(that: Certificates): Certificates =
      Certificates(evidence + that.evidence, atcs + that.atcs, layeredAtcs + that.layeredAtcs,
        distances ++ that.distances, contexts ++ that.contexts)
  }
  def depth(context: Evidence.AnswerContext): Option[Int] = context match {
    case Evidence.AnswerContext.Compose(a, b) => for x <- depth(a); y <- depth(b) yield x + y
    case Evidence.AnswerContext.Variable(_) => None
    case Evidence.AnswerContext.Hole => Some(0)
    case Evidence.AnswerContext.Layer(_, _, rest, _) => depth(rest).map(_ + 1)
  }
  private def sum(xs: Iterable[Certificates]): Certificates = xs.foldLeft(Certificates())(_ + _)
  private def value(v: Core.Value): Certificates = v.node match {
    case Core.ValueNode.Constructor(_, vs) => sum(vs.map(value))
    case Core.ValueNode.Primitive(_, vs) => sum(vs.map(value))
    case Core.ValueNode.Lambda(_, _, body) => term(body)
    case Core.ValueNode.LocalLambda(_, _, body) => term(body)
    case Core.ValueNode.CapabilityLambda(_, _, _, body) => value(body)
    case Core.ValueNode.Instantiate(f, c, es) => value(f) + value(c) + Certificates(evidence = es.size)
    case Core.ValueNode.KindLambda(_, _, body) => value(body)
    case Core.ValueNode.InstantiateKind(body, _: effekt.atm.Annotations.Argument.Evidence) => value(body) + Certificates(evidence = 1)
    case Core.ValueNode.InstantiateKind(body, _) => value(body)
    case Core.ValueNode.TypeLambda(_, body) => value(body)
    case Core.ValueNode.InstantiateType(body, _) => value(body)
    case Core.ValueNode.ProofAbstraction(body) => value(body)
    case Core.ValueNode.ProofApplication(body) => value(body)
    case _ => Certificates()
  }
  private def term(t: Core.Term): Certificates = t.node match {
    case Core.Node.Return(v) => value(v)
    case Core.Node.Assert => Certificates()
    case Core.Node.Let(_, a, b) => term(a) + term(b)
    case Core.Node.LetRec(_, f, b) => value(f) + term(b)
    case Core.Node.Apply(f, a, es) => value(f) + value(a) + Certificates(evidence = es.size)
    case Core.Node.If(c, a, b) => value(c) + term(a) + term(b)
    case Core.Node.Match(v, cases) => value(v) + sum(cases.map(c => term(c.body)))
    case Core.Node.Perform(_, _, arg, ev, typing) =>
      value(arg) + Certificates(1, 1, if ev.distance.concrete.exists(_ > 0) then 1 else 0,
        List(ev.distance.concrete), List(typing.context))
    case Core.Node.Handle(_, _, body, _, ret, ops) => term(body) + term(ret) + sum(ops.map(o => term(o.body)))
    case Core.Node.Ascribe(body) => term(body)
  }
  def inspect(program: Syntax.Program): Either[Syntax.Diagnostic, Certificates] =
    Checker.check(program).map(p => term(Core.elaborate(p).body))

  private def quote(s: String): String = "\"" + s.flatMap {
    case '"' => "\\\""
    case '\\' => "\\\\"
    case c if c < ' ' => f"\\u${c.toInt}%04x"
    case c => c.toString
  } + "\""
  private def outcome(result: Either[Syntax.Diagnostic, ?]): String =
    result.fold(_.kind.toString, _ => "accepted")

  def main(args: Array[String]): Unit = {
    require(args.length >= 2 && Set("inspect", "time")(args(0)),
      "AtmBenchmark inspect FILE... | time WARMUP_ROUNDS MEASURED_ROUNDS FILE...")
    val timing = args(0) == "time"
    require(args.length >= (if timing then 4 else 2))
    val warmups = if timing then args(1).toInt else 0
    val rounds = if timing then args(2).toInt else 0
    require(!timing || (warmups >= 1 && rounds >= 3))
    val inputs = args.drop(if timing then 3 else 1).toList.map { name =>
      val source = Files.readString(Path.of(name))
      name -> Parser.parse(source).fold(e => throw e, identity)
    }
    require(inputs.nonEmpty)
    if !timing then inputs.foreach { (name, program) =>
      val inspection = Checker.inspect(program)
      val result = inspection.result.map(p => term(Core.elaborate(p).body))
      val details = result match {
        case Right(c) => s"\"evidence_uses\":${c.evidence},\"operation_atcs\":${c.atcs},\"layered_atcs\":${c.layeredAtcs}," +
          s"\"operation_distances\":${c.distances.map(_.fold("null")(_.toString)).mkString("[", ",", "]")}," +
          s"\"atc_depths\":${c.contexts.map(c => depth(c).fold("null")(_.toString)).mkString("[", ",", "]")}," +
          s"\"atc_structures\":${c.contexts.map(c => quote(c.toString)).mkString("[", ",", "]") }"
        case Left(e) =>
          val failure = inspection.operations.lastOption.filter(_.context.isLeft).map { o =>
            val w = o.evidence.witness
            s",\"operation_failure\":{\"receiver\":${quote(o.receiver)},\"operation\":${quote(o.operation)}," +
              s"\"evidence\":${quote(w.toString)},\"from\":${quote(w.constraint.from)},\"to\":${quote(w.constraint.to)}," +
              s"\"distance\":${w.distance.concrete.fold("null")(_.toString)}," +
              s"\"residuals\":${o.evidence.residuals.map(r => quote(r.toString)).mkString("[", ",", "]")}}"
          }.getOrElse("")
          s"\"message\":${quote(e.message)}$failure"
      }
      println(s"{\"file\":${quote(name)},\"outcome\":${quote(outcome(result))},$details}")
    }
    else {
      val outcomes = scala.collection.mutable.Map.empty[String, String]
      val samples = inputs.map((name, _) => name -> scala.collection.mutable.ListBuffer.empty[Double]).toMap
      def run(name: String, program: Syntax.Program, measured: Boolean): Unit = {
        val start = if measured then System.nanoTime() else 0L
        val result = Checker.check(program)
        val elapsed = if measured then (System.nanoTime() - start) / 1000000.0 else 0.0
        val actual = outcome(result)
        outcomes.get(name).foreach(expected => require(expected == actual, s"Unstable outcome for $name"))
        outcomes(name) = actual
        if measured then samples(name) += elapsed
      }
      for round <- 0 until warmups + rounds do {
        val shuffled = new scala.util.Random(152L + round).shuffle(inputs)
        shuffled.foreach((name, program) => run(name, program, round >= warmups))
        System.err.println(s"Completed ${if round < warmups then "warm-up" else "measured"} round ${round + 1}")
      }
      inputs.foreach { (name, _) =>
        println(s"{\"file\":${quote(name)},\"outcome\":${quote(outcomes(name))},\"samples_ms\":${samples(name).mkString("[", ",", "]")}}")
      }
    }
  }
}

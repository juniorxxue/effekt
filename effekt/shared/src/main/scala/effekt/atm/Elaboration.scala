package effekt.atm

import Syntax.*
import Checker.*
import Evidence.*

/** Inference certificates are retained here and checked for closure before
 * Core elaboration and direct Lifted lowering (or the legacy source emitter). This
 * validation is an implementation invariant, not the paper's soundness proof.
 */
object Elaboration {
  def substitute(t: CheckedTerm, sigma: Map[String, Witness]): CheckedTerm = t.copy(
    values = t.values.map(substitute(_, sigma)), children = t.children.map(substitute(_, sigma)),
    operations = t.operations.map(o => o.copy(body = substitute(o.body, sigma))),
    evidence = t.evidence.map(_.substitute(sigma)), residuals = t.residuals.filterNot(o => sigma.contains(o.name)))
  def substitute(v: CheckedValue, sigma: Map[String, Witness]): CheckedValue = v.copy(
    children = v.children.map(substitute(_, sigma)), body = v.body.map(substitute(_, sigma)),
    evidence = v.evidence.map(_.substitute(sigma)), residuals = v.residuals.filterNot(o => sigma.contains(o.name)))

  def validate(root: CheckedTerm): Unit = {
    def witness(w: Witness, bindings: Map[String, Binding], regions: Set[String]): Unit = {
      if !(w.constraint.free subsetOf regions) then throw Diagnostic("Elaboration contains an unbound region")
      w match {
        case Witness.Use(b) if !bindings.get(b.name).contains(b) =>
          throw Diagnostic(s"Elaboration contains an unbound or mistyped evidence variable ${b.name}")
        case Witness.Compose(a, b) => witness(a, bindings, regions); witness(b, bindings, regions)
        case _ => ()
      }
    }
    def value(v: CheckedValue, bindings: Map[String, Binding], regions: Set[String]): Unit = {
      v.evidence.foreach(witness(_, bindings, regions))
      val rs = v.source match {
        case Value.CapabilityLambda(r, _, _, _) => regions + r
        case Value.KindLambda(r, Annotations.Kind.Region, _) => regions + r
        case _ => regions
      }
      val bs = bindings ++ v.quantified.map(b => b.name -> b)
      v.children.foreach(value(_, bs, rs))
      v.abstraction match {
        case Some(a) =>
          if a.call.constraint.from != a.region then throw Diagnostic("A_Abs has a malformed call witness")
          if !(a.call.constraint.free - a.region subsetOf rs) then throw Diagnostic("A_Abs requires an out-of-scope region")
          v.body.foreach(term(_, bs.updated(a.call.name, a.call), rs + a.region))
        case None => v.body.foreach(term(_, bs, rs))
      }
    }
    def term(t: CheckedTerm, bindings: Map[String, Binding], regions: Set[String]): Unit = {
      t.evidence.foreach(witness(_, bindings, regions))
      t.values.foreach(value(_, bindings, regions))
      t.source match {
        case _: Term.Perform if t.evidence.size != 1 => throw Diagnostic("A_Do is missing its evidence argument")
        case _: Term.Apply => t.values.head.tpe match {
          case _: Type.Function if t.evidence.size != 1 => throw Diagnostic("A_App is missing its evidence argument")
          case _: Type.Continuation if t.evidence.nonEmpty => throw Diagnostic("A_KApp must be a plain application")
          case _: Type.FixedFunction if t.evidence.nonEmpty => throw Diagnostic("A local call must be a plain application")
          case _ => ()
        }
        case _ => ()
      }
      t.handlerEvidence match {
        case Some(edge) =>
          term(t.children.head, bindings.updated(edge.name, edge), regions + edge.constraint.from)
          t.children.drop(1).foreach(term(_, bindings, regions))
        case None =>
          // The recursive definition is already under its abstraction in values.
          val children = if t.source.isInstanceOf[Term.LetRec] then t.children.drop(1) else t.children
          children.foreach(term(_, bindings, regions))
      }
      t.operations.foreach(o => term(o.body, bindings, regions))
    }
    term(root, Map.empty, Set.empty)
  }

  def show(root: CheckedTerm): String = {
    val out = new StringBuilder
    def line(depth: Int, text: String): Unit = { out.append("  " * depth).append(text).append('\n'); () }
    def proof(w: Witness): String = w match {
      case Witness.Refl(r) => s"0e[$r]"
      case Witness.Use(b) => b.name
      case Witness.Compose(a, b) => s"(${proof(a)} + ${proof(b)})"
    }
    def value(v: CheckedValue, depth: Int): Unit = {
      line(depth, s"${v.source.productPrefix}: ${Syntax.show(v.tpe)}")
      v.evidence.foreach(e => line(depth + 1, s"instantiate ${e.constraint}; distance=${e.distance}; evidence=${proof(e)}"))
      v.quantified.foreach(b => line(depth + 1, s"Λ ${b.distance}, ${b.name}: ${b.constraint}"))
      v.abstraction.foreach(a => line(depth + 1, s"Λ ${a.region}:Reg, ${a.call.distance}:Dist, ${a.call.name}:Ev[${a.call.constraint}]"))
      v.children.foreach(value(_, depth + 1))
      v.body.foreach(term(_, depth + 1))
    }
    def term(t: CheckedTerm, depth: Int): Unit = {
      line(depth, s"${t.source.productPrefix}: ${Syntax.show(t.tpe)}")
      t.handlerEvidence.foreach(b => line(depth + 1, s"bind ${b.constraint.from}:Reg, ${b.name}: ${b.constraint} at distance 1"))
      t.evidence.foreach(e => line(depth + 1, s"${e.constraint}; distance=${e.distance}; evidence=${proof(e)}"))
      t.values.foreach(value(_, depth + 1))
      (if t.source.isInstanceOf[Term.LetRec] then t.children.drop(1) else t.children).foreach(term(_, depth + 1))
      t.operations.foreach(o => term(o.body, depth + 1))
    }
    term(root, 0)
    out.result()
  }
}

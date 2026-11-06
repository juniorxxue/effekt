package effekt.atm

/** Total logical functions defined by structural recursion on their first data
 * argument. These are definitions, never user-provided axioms or SMT text.
 */
object Measures {
  import Logic.*
  import Syntax.Diagnostic

  def validate(definitions: List[Measure], data: Map[String, List[(String, List[Sort])]]): Map[String, Measure] = {
    def require(p: Boolean, message: String): Unit = if !p then throw Diagnostic(message)
    require(definitions.map(_.name).distinct.size == definitions.size, "Duplicate measure name")
    definitions.foldLeft(Map.empty[String, Measure]) { (previous, m) =>
      require(m.name.matches("[a-zA-Z_][a-zA-Z0-9_]*"), "Invalid measure name")
      require(m.parameters.nonEmpty && m.parameters.map(_._1).distinct.size == m.parameters.size, "A measure needs distinct parameters")
      val context = Context(datatypes = data, measures = previous.updated(m.name, m))
      val owner = m.parameters.head._2 match {
        case Sort.Data(n) if data.contains(n) => n
        case _ => throw Diagnostic("A measure must recurse on a declared datatype as its first parameter")
      }
      m.parameters.foreach(p => validateSort(p._2, context)); validateSort(m.result, context)
      val constructors = data(owner)
      require(m.cases.map(_.constructor).distinct.size == m.cases.size &&
        m.cases.map(_.constructor).toSet == constructors.map(_._1).toSet, "Measure cases must cover each constructor exactly once")
      m.cases.foreach { c =>
        val fields = constructors.find(_._1 == c.constructor).get._2
        require(c.parameters.size == fields.size && c.parameters.distinct.size == c.parameters.size, "Invalid measure pattern fields")
        require((c.parameters.toSet intersect m.parameters.map(_._1).toSet).isEmpty, "A measure pattern cannot shadow its parameters")
        val smaller = c.parameters.zip(fields).collect { case (n, Sort.Data(`owner`)) => n }.toSet
        def decreasing(e: Expr): Unit = e match {
          case Expr.App(op, args) =>
            if op == "measure:" + m.name then require(args.headOption.exists {
              case Expr.Variable(n) => smaller(n)
              case _ => false
            }, "A recursive measure call must use a direct recursive constructor field")
            args.foreach(decreasing)
          case Expr.Predicate(_, _) => throw Diagnostic("Measures cannot assume uninterpreted predicates")
          case _: Expr.Forall | _: Expr.Exists => throw Diagnostic("Measure bodies cannot introduce logical binders")
          case _ => ()
        }
        decreasing(c.body)
        val scope = (m.parameters ++ c.parameters.zip(fields)).foldLeft(context) { case (e, (n, s)) => e.bind(n, s) }
        require(sort(c.body, scope) == m.result, "Measure body has the wrong result sort")
      }
      previous.updated(m.name, m)
    }
  }
}

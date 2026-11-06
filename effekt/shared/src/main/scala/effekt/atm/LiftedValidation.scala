package effekt.atm

import effekt.{lifted as L}
import effekt.core.Id
import Syntax.Diagnostic

/** A structural type/scope checker for the Lifted subset emitted by ATM.
 * Historical Lifted's .tpe fields synthesize types but do not check applications
 * or variable annotations. Check these before invoking its untyped backend.
 */
object LiftedValidation {
  def same(a: L.ValueType, b: L.ValueType): Boolean = (a, b) match {
    case (L.ValueType.Var(x), L.ValueType.Var(y)) => x == y
    case (L.ValueType.Data(x, xs), L.ValueType.Data(y, ys)) => x == y && xs.size == ys.size && xs.zip(ys).forall((x, y) => same(x, y))
    case (L.ValueType.Boxed(x), L.ValueType.Boxed(y)) => same(x, y)
    case _ => false
  }
  def same(a: L.BlockType, b: L.BlockType): Boolean = (a, b) match {
    case (x: L.BlockType.Function, y: L.BlockType.Function) if x.tparams.size == y.tparams.size =>
      val rhs = L.Type.instantiate(y, x.tparams.map(L.ValueType.Var.apply))
      x.eparams.size == rhs.eparams.size && x.vparams.size == rhs.vparams.size && x.bparams.size == rhs.bparams.size &&
        x.vparams.zip(rhs.vparams).forall((x, y) => same(x, y)) && x.bparams.zip(rhs.bparams).forall((x, y) => same(x, y)) && same(x.result, rhs.result)
    case (L.BlockType.Interface(x, xs), L.BlockType.Interface(y, ys)) => x == y && xs.size == ys.size && xs.zip(ys).forall((x, y) => same(x, y))
    case _ => false
  }
  private case class Env(values: Map[Id, L.ValueType] = Map.empty, blocks: Map[Id, L.BlockType] = Map.empty,
      evidence: Set[Id] = Set.empty, types: Set[Id] = Set.empty)

  def check(module: L.ModuleDecl): Unit = {
    def require(ok: Boolean, message: => String): Unit = if !ok then throw Diagnostic("Lifted validation: " + message)
    val declarations = new L.DeclarationContext(module.decls)
    def vt(t: L.ValueType, e: Env): Unit = t match {
      case L.ValueType.Var(id) => require(e.types(id), "unbound type variable " + id)
      case L.ValueType.Data(_, args) => args.foreach(vt(_, e))
      case L.ValueType.Boxed(t) => bt(t, e)
    }
    def bt(t: L.BlockType, e: Env): Unit = t match {
      case f: L.BlockType.Function =>
        val inner = e.copy(types = e.types ++ f.tparams)
        f.vparams.foreach(vt(_, inner)); f.bparams.foreach(bt(_, inner)); vt(f.result, inner)
      case L.BlockType.Interface(id, args) =>
        val d = declarations.findInterface(id).getOrElse(throw Diagnostic("Lifted validation: unknown interface"))
        require(d.tparams.size == args.size, "interface type arity")
        args.foreach(vt(_, e))
    }
    def evidence(ev: L.Evidence, e: Env): Unit = ev.lifts.foreach {
      case L.Lift.Var(id) => require(e.evidence(id), "unbound evidence " + id)
      case _ => ()
    }
    def bind(ps: List[L.Param], e: Env): Env = ps.foldLeft(e) {
      case (e, L.Param.ValueParam(id, t)) => vt(t, e); e.copy(values = e.values.updated(id, t))
      case (e, L.Param.BlockParam(id, t)) => bt(t, e); e.copy(blocks = e.blocks.updated(id, t))
      case (e, L.Param.EvidenceParam(id)) => e.copy(evidence = e.evidence + id)
    }
    def app(b: L.Block, ts: List[L.ValueType], args: List[L.Argument], e: Env): Unit = {
      block(b, e); ts.foreach(vt(_, e))
      val f = b.tpe match {
        case f: L.BlockType.Function => f
        case _ => throw Diagnostic("Lifted validation: application of non-function")
      }
      require(f.tparams.size == ts.size, "type argument arity")
      val instantiated = L.Type.instantiate(f, ts)
      val expected: List[L.Type] = instantiated.eparams ++ instantiated.vparams ++ instantiated.bparams
      require(expected.size == args.size, "argument arity")
      expected.zip(args).foreach {
        case (_: L.EvidenceType, ev: L.Evidence) => evidence(ev, e)
        case (t: L.ValueType, v: L.Expr) => expr(v, e); require(same(t, v.tpe), s"value argument type $t != ${v.tpe}")
        case (t: L.BlockType, b: L.Block) => block(b, e); require(same(t, b.tpe), "block argument type")
        case _ => throw Diagnostic("Lifted validation: argument kind/order")
      }
    }
    def expr(x: L.Expr, e: Env): Unit = {
      x match {
        case L.Expr.ValueVar(id, t) => require(e.values.get(id).exists(same(_, t)), "unbound/mistyped value " + id)
        case L.Expr.Literal(v, t) =>
          val valid = v match {
            case _: BigInt => same(t, L.Type.TInt)
            case _: Boolean => same(t, L.Type.TBoolean)
            case _: String => same(t, L.Type.TString)
            case () => same(t, L.Type.TUnit)
            case _ => false
          }
          require(valid, "literal type")
        case L.Expr.PureApp(b, ts, args) =>
          b match {
            case L.Block.BlockVar(id, _) => require(module.externs.exists { case L.Extern.Def(n, _, _, _, _) => n == id; case _ => false }, "PureApp must target a primitive")
            case _ => throw Diagnostic("Lifted validation: non-primitive PureApp")
          }
          app(b, ts, args, e)
        case L.Expr.Make(data, tag, args) =>
          val c = declarations.constructors.getOrElse(tag, throw Diagnostic("Lifted validation: unknown constructor"))
          require(c.data.id == data.name && c.data.tparams.size == data.targs.size, "constructor data type")
          val subst = c.data.tparams.zip(data.targs).toMap
          require(c.constructor.fields.size == args.size, "constructor arity")
          c.constructor.fields.zip(args).foreach { (f, a) => expr(a, e); require(same(L.Type.substitute(f.tpe, subst), a.tpe), "constructor field type") }
        case L.Expr.Select(receiver, field, t) =>
          expr(receiver, e)
          val f = declarations.fields.getOrElse(field, throw Diagnostic("Lifted validation: unknown field"))
          receiver.tpe match {
            case L.ValueType.Data(id, args) =>
              require(id == f.data.id, "field receiver")
              require(same(t, L.Type.substitute(f.field.tpe, f.data.tparams.zip(args).toMap)), "field result annotation")
            case _ => throw Diagnostic("Lifted validation: field access on non-data")
          }
        case L.Expr.Box(b) => block(b, e)
        case L.Expr.Run(s) => stmt(s, e)
      }
      vt(x.tpe, e)
    }
    def block(b: L.Block, e: Env): Unit = b match {
      case L.Block.BlockVar(id, t) => require(e.blocks.get(id).exists(same(_, t)), "unbound/mistyped block " + id)
      case L.Block.BlockLit(ts, ps, body) => stmt(body, bind(ps, e.copy(types = e.types ++ ts)))
      case L.Block.Unbox(x) => expr(x, e); require(x.tpe.isInstanceOf[L.ValueType.Boxed], "unbox non-box")
      case L.Block.Member(receiver, field, t) =>
        block(receiver, e)
        val f = declarations.properties.getOrElse(field, throw Diagnostic("Lifted validation: unknown method"))
        receiver.tpe match {
          case L.BlockType.Interface(id, args) =>
            require(id == f.interface.id, "method receiver")
            require(same(t, L.Type.substitute(f.property.tpe, f.interface.tparams.zip(args).toMap)), "method type annotation")
          case _ => throw Diagnostic("Lifted validation: method receiver is not an interface")
        }
      case L.Block.New(impl) => implementation(impl, e)
    }
    def implementation(i: L.Implementation, e: Env): Unit = {
      bt(i.interface, e)
      val d = declarations.interfaces(i.interface.name)
      require(i.operations.map(_.name).toSet == d.properties.map(_.id).toSet && i.operations.size == d.properties.size, "handler method set")
      val substitution = d.tparams.zip(i.interface.targs).toMap
      i.operations.foreach { op =>
        block(op.implementation, e)
        val expected = L.Type.substitute(d.properties.find(_.id == op.name).get.tpe, substitution)
        require(same(expected, op.implementation.tpe), "handler method signature")
      }
    }
    def definitions(ds: List[L.Definition], e: Env): Env = {
      var inner = e.copy(blocks = e.blocks ++ ds.collect { case L.Definition.Def(id, b) => id -> b.tpe })
      ds.foreach {
        case L.Definition.Def(_, b) => block(b, inner)
        case L.Definition.Let(id, x) => expr(x, inner); inner = inner.copy(values = inner.values.updated(id, x.tpe))
      }
      inner
    }
    def stmt(s: L.Stmt, e: Env): Unit = {
      s match {
        case L.Stmt.Return(x) => expr(x, e)
        case L.Stmt.Scope(ds, body) => stmt(body, definitions(ds, e))
        case L.Stmt.Val(id, binding, body) => stmt(binding, e); stmt(body, e.copy(values = e.values.updated(id, binding.tpe)))
        case L.Stmt.App(b, ts, args) => app(b, ts, args, e)
        case L.Stmt.If(c, a, b) =>
          expr(c, e); require(same(c.tpe, L.Type.TBoolean), "condition type")
          stmt(a, e); stmt(b, e); require(same(a.tpe, b.tpe), "branch runtime types")
        case L.Stmt.Match(scrutinee, clauses, default) =>
          expr(scrutinee, e)
          val data = scrutinee.tpe match {
            case d: L.ValueType.Data => d
            case _ => throw Diagnostic("Lifted validation: match requires data")
          }
          val declaration = declarations.findData(data.name).getOrElse(throw Diagnostic("Lifted validation: unknown match datatype"))
          require(clauses.map(_._1).distinct.size == clauses.size, "duplicate match clause")
          require(default.nonEmpty || clauses.map(_._1).toSet == declaration.constructors.map(_.id).toSet, "nonexhaustive match")
          require(data.targs.size == declaration.tparams.size, "match type arity")
          val subst = declaration.tparams.zip(data.targs).toMap
          clauses.foreach { (tag, clause) =>
            val ctor = declaration.constructors.find(_.id == tag).getOrElse(throw Diagnostic("Lifted validation: wrong match constructor"))
            require(clause.tparams.isEmpty && clause.params.size == ctor.fields.size, "match clause arity")
            clause.params.zip(ctor.fields).foreach {
              case (L.Param.ValueParam(_, t), f) => require(same(t, L.Type.substitute(f.tpe, subst)), "match field type")
              case _ => throw Diagnostic("Lifted validation: match clause parameter kind")
            }
            block(clause, e); require(same(clause.body.tpe, s.tpe), "match result type")
          }
          default.foreach(d => { stmt(d, e); require(same(d.tpe, s.tpe), "match default result type") })
        case L.Stmt.Handle(body, hs, ret, result) =>
          block(body, e); hs.foreach(implementation(_, e)); block(ret, e); vt(result, e)
          require(body.tparams.isEmpty && body.params.size == hs.size + 1 && body.params.head.isInstanceOf[L.Param.EvidenceParam], "handler body parameters")
          body.params.tail.zip(hs).foreach {
            case (L.Param.BlockParam(_, t), h) => require(same(t, h.interface), "handler capability parameter")
            case _ => throw Diagnostic("Lifted validation: handler parameter kind")
          }
          require(ret.tparams.isEmpty && ret.params.size == 1, "return clause arity")
          ret.params.head match {
            case L.Param.ValueParam(_, t) => require(same(t, body.body.tpe), "return clause input type")
            case _ => throw Diagnostic("Lifted validation: return clause parameter kind")
          }
        case L.Stmt.Shift(ev, body) =>
          evidence(ev, e)
          body.params match {
            case List(L.Param.BlockParam(_, f: L.BlockType.Function)) =>
              require(f.eparams.size == 1 && f.vparams.size == 1 && f.bparams.isEmpty && f.tparams.isEmpty, "resumption signature")
            case _ => throw Diagnostic("Lifted validation: shift binder")
          }
          block(body, e)
        case _ => throw Diagnostic("Lifted validation: unsupported node in direct ATM target: " + s.productPrefix)
      }
      vt(s.tpe, e)
    }
    val externalTypes = module.externs.collect { case L.Extern.Def(id, ts, ps, ret, _) =>
      id -> L.BlockType.Function(ts, ps.collect { case _: L.Param.EvidenceParam => L.EvidenceType() },
        ps.collect { case L.Param.ValueParam(_, t) => t }, ps.collect { case L.Param.BlockParam(_, t) => t }, ret)
    }.toMap
    definitions(module.definitions, Env(blocks = externalTypes))
  }
}

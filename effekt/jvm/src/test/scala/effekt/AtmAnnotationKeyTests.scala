package effekt

import effekt.context.Annotations

/** Regression for lost annotations during repeated compiler runs. Identity
 * hashes may collide; an identity-keyed map must still retain both nodes.
 */
class AtmAnnotationKeyTests extends munit.FunSuite {
  private def colliding(value: AnyRef): Annotations.HashKey[AnyRef] = new Annotations.HashKey(value) {
    override val hashCode = 7
  }

  test("annotation keys distinguish different nodes with the same identity hash") {
    val a = new Object
    val b = new Object
    val first = colliding(a)
    val second = colliding(b)
    assert(first != second)
    val annotations = Map(first -> "first", second -> "second")
    assertEquals(annotations.size, 2)
    assertEquals(annotations(colliding(a)), "first")
    assertEquals(annotations(colliding(b)), "second")
  }

  test("annotation keys compare the underlying identity even through different wrappers") {
    val node = new Object
    assertEquals(new Annotations.HashKey(node), new Annotations.HashKey(node))
  }

  test("value-keyed annotations retain v0.3.0 structural equality") {
    case class Node(value: Int)
    val identity = effekt.context.Annotation[Node, String]("identity", "identity lookup")
    val structural = effekt.context.Annotation[Node, String]("structural", "value lookup", bindToObjectIdentity = false)
    val annotations = Annotations.empty
    val first = Node(42)
    val second = Node(42)
    annotations.update(identity, first, "first")
    annotations.update(structural, first, "first")
    assertEquals(annotations.get(identity, second), None)
    assertEquals(annotations.get(structural, second), Some("first"))
  }
}

package difflicioustest

import munit.ScalaCheckSuite
import DifferAutoDerivationSpec.P1

class DifferAutoDerivationSpec extends ScalaCheckSuite {
  test("should not compile without instance in scope") {
    val result = compileErrors(
      """
        import difflicious._
        final case class P1(f1: String)
        
        val p1: Differ[P1] = Differ.derived[P1]

        Differ[P1].diff(P1("a"), P1("b"))
        """)
    assertNoDiff(
      result,
      """error: No given instance of type difflicious.Differ[P1] was found for parameter differ of method apply in object Differ
        |        Differ[P1].diff(P1("a"), P1("b"))
        |                 ^
        |""".stripMargin
    )
  }
  test("should find auto derived instance for product") {
    val result = compileErrors(
      """
        import difflicious._
        import difflicious.generic.auto.given
        
        Differ[P1].diff(P1("a"), P1("b"))
        """)
    assertNoDiff(result, "")
  }
  test("should put auto derived instance back into scope") {
    val result = compileErrors(
      """
        import difflicious._
        import difflicious.generic.auto.given

        implicit val d: Differ[P1] = implicitly[Derived[P1]].differ
        
        Differ[P1].diff(P1("a"), P1("b"))
        """)
    assertNoDiff(result, "")
  }
  test("should use manually defined instance for an element") {
    import difflicious._
    import difflicious.generic.auto._
    import difflicious.generic.auto.given

    final case class Pi(f1: String, f2: String)
    final case class P2(p1: Pi)
    implicit val d: Differ[Pi] = implicitly[Derived[Pi]].differ.ignoreAt(_.f1)

    val r = Differ[P2].diff(P2(Pi("a", "a")), P2(Pi("b", "a")))
    assertEquals(r.isOk, true)
  }
}
  
object DifferAutoDerivationSpec {
  final case class P1(f1: String)
}

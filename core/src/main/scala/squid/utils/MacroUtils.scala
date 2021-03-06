package squid.utils

import annotation.StaticAnnotation
import reflect.api.Position

object MacroUtils {
  import scala.reflect.macros.whitebox.Context

  type DebugLevel = NoDebug
  // type DebugLevel = MacroDebug

  trait DebugInfo

  trait NoDebug extends DebugInfo
  def NoDebug = new NoDebug {}

  trait MacroDebug extends DebugInfo
  def MacroDebug = new MacroDebug {}

  trait ApplyDebug extends MacroDebug
  def ApplyDebug = new ApplyDebug {}

  trait UnapplyDebug extends MacroDebug
  def UnapplyDebug = new UnapplyDebug {}

  trait TypeParamDebug extends MacroDebug
  def TypeParamDebug = new TypeParamDebug {}

  trait TypeInferDebug extends MacroDebug
  def TypeInferDebug = new TypeInferDebug {}

  private[squid] class MacroSetting(debug: Boolean) extends StaticAnnotation

  private[squid] case class MacroDebugger[C <: Context](c: C) {

    import c.universe._

    val debugOption = c.macroApplication.symbol.annotations.filter(
      _.tree.tpe <:< typeOf[MacroSetting]
    ).headOption.flatMap(_.tree.children.tail.collectFirst {
        case q"true"  => true
        case q"false" => false
      }).getOrElse(false)

    def apply[Dbg <: DebugInfo: c.TypeTag] = {
      val dbgt = c.typeOf[Dbg]
      new Debugger(debugOption || dbgt <:< c.typeOf[DebugLevel] || {
        val edb = c.inferImplicitValue(c.typeOf[DebugInfo])
        dbgt <:< edb.tpe
      })
    }
    class Debugger(enabled: Boolean) {
      /**
       * Some IDEs (eg: Idea) display prints from macros as warnings, and prevents their repetition, hampering macro
       * debugging. This prints a unique integer in front of the message to always print it.
       */
      def apply(x: => Any, xs: Any*): Unit = if (enabled) {
        println(debugNum + "> " + ((x +: xs) mkString "  "))
        debugNum += 1
      }
      def pos(x: => Any, xs: Any*): Unit = if (enabled) {
        c.warning(c.enclosingPosition, debugNum + "> " + ((x +: xs) mkString "  "))
        debugNum += 1
      }
      def debugOptionEnabled = debugOption
      
    }
    
  }

  private var debugNum = 0
  
  // cf: https://issues.scala-lang.org/browse/SI-7934
  @deprecated("", "") class Deprecated { def lineContentForwarder = (pos: Position)=>pos.lineContent };
  object Deprecated extends Deprecated
  
  private[squid] def showPosition(pos: Position, indent: String = "  ") = {
    val line = Deprecated.lineContentForwarder(pos)
    if (line exists (_ != ' ')) (
      indent + line + "\n"
      + indent + (" " * pos.column) + "^\n"
    )
    else ""
  }
  
  //private[scp] def showCodeOpen(t: Context# Tree) = {
  
  // Note: depending on where it comes from (typed tree or external symbol), Scala will not always display vararg symbols with the same name... (thanks for that!)
  val SCALA_REPEATED = Set("scala.<repeated>", "scala.<repeated...>")
  
}














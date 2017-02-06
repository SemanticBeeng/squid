package squid
package anf

import ir._

/**
  * Created by lptk on 03/02/17.
  */
class TransformationControlTests extends MyFunSuite(SimpleANFTests.DSL) {
  import DSL.Predef._
  
  import collection.mutable.ArrayBuffer
  
  test("Predef.Abort") {
    
    val c0 = ir"val a = ArrayBuffer[Int](1,2,3); println(a(1)); a.size"
    def f(x: IR[_,{}]) = x rewrite {
      case ir"val arr = ArrayBuffer[$t]($xs*); $body: $bt" =>
        body subs 'arr -> Abort()
    }
    f(c0) eqt c0
    f(ir"val a = ArrayBuffer[Int](1,2,3); println('ok)") eqt ir"println('ok)"
    
    intercept[IllegalAccessError](Abort())
    
  }
  
  test("Predef.Return") {
    
    val a = ir"println(12.toDouble); println(identity(42)+1); 666"
    val b = a rewrite {
      case ir"($x:Int)+($y:Int)" =>
        Return(ir"$x+$y")
      case ir"(${Const(n)}:Int)" => Const(n+1)
    }
    
    // The `1` constant has not been incremented because it was part of the early return (43 was let-bound outisde of it)
    b eqt ir"println(13.toDouble); println(identity(43)+1); 667"
    
  }
  
  test("Predef.Return.transforming trivial expression") {
    
    def f(rep: IR[_,{}]) = rep rewrite {
      case ir"ArrayBuffer($x:Int,$y:Int)" =>
        Return.transforming(x)(x => ir"ArrayBuffer($x,$y)")
      case ir"(${Const(n)}:Int)" => Const(n+1)
    }
    
    f(ir"ArrayBuffer(50,60)") eqt ir"ArrayBuffer(51,60)"
    f(ir"ArrayBuffer(50,60);println") eqt ir"ArrayBuffer(51,60);println"
    f(ir"val a = ArrayBuffer(50,60);println(a)") eqt ir"val a = ArrayBuffer(51,60);println(a)"
    
     val a0 = ir"ArrayBuffer(50,60,70)" rewrite {
      case ir"ArrayBuffer[Int]($x,$y,$z)" =>
        Return.transforming(x,z)((x,z) => ir"ArrayBuffer($x,$x,$y,$y,$z,$z)")
      case ir"(${Const(n)}:Int)" => Const(n+1)
    }
    a0 eqt ir"ArrayBuffer(51,51,60,60,71,71)"
    
     val a1 = ir"ArrayBuffer(50,60,70)" rewrite {
      case ir"ArrayBuffer[Int]($x,$y,$z)" =>
        //Return.transforming(x::z::Nil)(_ |>! {case x::z::Nil => ir"ArrayBuffer($x,$x,$y,$y,$z,$z)"}) // probably does not compile because of the patmat
        Return.transforming(x::z::Nil){ls => val x = ls(0); val z = ls(1); ir"ArrayBuffer($x,$x,$y,$y,$z,$z)"}
      case ir"(${Const(n)}:Int)" => Const(n+1)
    }
    a1 eqt ir"ArrayBuffer(51,51,60,60,71,71)"
    
  }
  
  test("Predef.Return.transforming blocks") {
    
    {
      val a = ir"println; println(if(true) println(42.toDouble) else println(666.toDouble))"
      val b = a rewrite {
        case ir"if($cond)$thn else $els: $t" =>
          Return.transforming(els)(e => ir"if($cond)$thn else $e")
        case ir"(${Const(n)}:Int)" => Const(n+1)
      }
      b eqt ir"println; println(if(true) println(42.toDouble) else println(667.toDouble))"
    }
    
    {
      val a = ir"println; if(readInt>0) println(42.toDouble,true) else println(666.toDouble,true); println(true)"
      val b = a rewrite {
        case ir"true" => ir"false"
        case ir"if(readInt>0)$thn else $els: $t" =>
          Return.transforming(els)(e => ir"if(true) $e else $thn")
        case ir"(${Const(n)}:Int)" => Const(n+1)
      }
      b eqt ir"println; if(true) println(667.toDouble,false) else println(42.toDouble,true); println(false)"
    }
    
    // Simpler example:
    {
      //val a = ir"readDouble; print(true.toString); readInt; println(true)"
      val a = ir"readDouble; print(true.toString); val x = readInt; if (true) println(x)"
      val b = a rewrite {
        case ir"true" => ir"false"
        //case ir"print($x)" => // does not produce several statements, so does not trigger the case we're testing
        case ir"print(($x:Boolean).toString)" =>
          Return.transforming(x)(x => ir"print($x)")
      }
      //b eqt ir"readDouble; print(false); readInt; println(false)"
      b eqt ir"readDouble; print(false); val x = readInt; if (false) println(x)"
    }
    
  }
  
  
  test("Predef.Return.recursing") {
    
    def f(x: IR[_,{}]) = x rewrite {
      case ir"val x: Int = $init; readInt+1; $body: $bt" =>
        Return.recursing { tr => val b = tr(body); ir"val x: Int = $init; readInt; $b" }
      case ir"readInt" => ir"???"
    }
    ir"readInt; val n = readInt; readInt+1; readInt; print(n)" |> f eqt 
      ir"???; val n = readInt; readInt; ???; print(n)"
    
    def g(x: IR[_,{}]) = x  rewrite {
      case ir"val x: Int = $init; readInt; $body: $bt" =>
        Return.recursing { tr => val b = tr(body); ir"val x: Int = $init; readDouble; $b" }
      case ir"readInt" => ir"???"
    }
    
    // What happens: first two readInt's are matched and the rest is transformed,
    // BUT the result refers to the second readInt, which was not named, so that fails
    // Instead, the second and third readInt are matched and transformed successfully
    ir"readInt; val n = readInt; readInt; readInt; print(n)" |> g eqt
      ir"readInt; val n = readInt; readDouble; ???; print(n)"
    ir"readInt; val n = readInt; readInt; readInt; print(n); readInt" |> g eqt
      ir"readInt; val n = readInt; readDouble; ???; print(n); ??? : Int"
    
    // Note: the second `readInt` is printed... because of a nasty subtle hygiene issue, due to recursively matching with the same xtor binding!
    ir"val n = readInt; readInt; readInt; readInt; print(n); readInt" |> g eqt
      ir"val n = readInt; readDouble; val m = readInt; readDouble; print(m); ??? : Int"
    
    // Shorter example:
    ir"val a = readInt; val b = readInt; print(a)" rewrite { case ir"val x = readInt; $body: $bt" => Return.transforming(body)(body => ir"val x = readDouble.toInt; $body") } eqt
    ir"val a = readDouble.toInt; val b = readDouble.toInt; print(b)"
    
    // While the version with implicit recursion does not have the problem: 
    ir"val a = readInt; val b = readInt; print(a)" rewrite { case ir"val x = readInt; $body: $bt" => ir"val x = readDouble.toInt; $body" } eqt
    ir"val a = readDouble.toInt; val b = readDouble.toInt; print(a)"
    
  }
  
  test("Context Enlargement") {
    
    var r = ir"lol?:Double; List[Int]()"
    
    r = ir"List[Int](readInt)" rewrite {
      case ir"readInt" =>
        Return(ir"(lol? : Double).toInt")
        ir"???"
    }
    
    assertDoesNotCompile("""
    r = ir"List[Int](readInt)" rewrite {
      case ir"readInt" =>
        Return(ir"(nope? : Double).toInt")
        ir"???"
    }
    """) // Error:(126, 32) type mismatch; found: squid.anf.SimpleANFTests.DSL.IR[List[Int],Any{val nope: Double}]; required: TransformationControlTests.this.DSL.IR[List[Int],Any{val lol: Double}]
    
    r eqt ir"List[Int]((lol? : Double).toInt)"
    
  }
  
  test("Bad Return Type") {
    
    assertDoesNotCompile("""
    ir"List[Int](readInt)" rewrite { case ir"readInt" => Return(ir"readDouble"); ir"???" }
    """) // Error:(140, 58) Cannot rewrite a term of type Int to a different type Double
    
  }
  
  
  test("Abort and Return in pattern guard") { // FIXME
    
    /*
    ir"List[Int](readInt)" rewrite {
      case ir"readInt" if {Return(ir"42"); true} => ir"???"
    } eqt ir"List[Int](42)"
    
    ir"List[Int](readInt)" rewrite {
      case ir"readInt" if Abort() => ir"???"
    } eqt ir"List[Int](readInt)"
    */
    
    // TODO (test nested RwR)
    
  }
  
  
  test("Early Return in Middle of Block") {
    
    val c0 = ir"print(1); print(2); print(3); print(4)"
    val c1 = c0 rewrite {
      case ir"print(2); print(3)" =>
        Return(ir"print(23)")
    }
    c1 eqt ir"print(1); print(23); print(4)"
    
    val a = ir"val aaa = readInt; val bbb = readDouble.toInt; (aaa+bbb).toDouble"
    val b = a rewrite {
      case ir"readDouble.toInt" =>
        Return(ir"readInt")
    }
    b eqt ir"val aa = readInt; val bb = readInt; (aa+bb).toDouble"
    
  }
  
  
  
}
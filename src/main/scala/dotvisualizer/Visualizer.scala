// See LICENSE for license details.

package dotvisualizer

import java.io.PrintWriter

import chisel3.experimental.ChiselAnnotation
import firrtl.PrimOps._
import firrtl._
import firrtl.annotations._
import firrtl.ir._
import firrtl.passes.Pass

import scala.collection.mutable
import sys.process._

//TODO: Chick: Allow specifying where to write dot file
//TODO: Chick: Allow way to suppress or minimize display of intermediate _T nodes
//TODO: Chick: Consider merging constants in to muxes and primops, rather than wiring in a node.

//scalastyle:off magic.number
/**
  * This library implements a graphviz dot file render.  The annotation can specify at what module to
  * start the rendering process.  value will eventually be modified to allow some options in rendering
  */

/**
  * Tools for creating annotations for direct processing of a firrtl input file.
  */
object VisualizerAnnotation {
  /**
    * Use this to create an firrtl annotation,
    * @param target  The module to start rendering
    * @param depth   Stop rendering after this many sub-modules have been descended. 0 just does IOs of target
    *                1 does all components of current target, plus IOs of submodules referenced,
    *                and so forth.
    * @return
    */
  def apply(target: Named, depth: Int = -1): Annotation = {
    Annotation(target, classOf[VisualizerTransform], s"${Visualizer.DepthString}=$depth")
  }

  /**
    * Use this to set the program to convert the dot file to a png.  dot and fdp seem to work well, others
    * might too.  Default is dot
    * @param program program to create png
    * @return
    */
  def setDotProgram(program: String): Annotation = {
    Annotation(CircuitTopName, classOf[VisualizerTransform], s"${Visualizer.DotProgramString}=$program")
  }

  /**
    * Use this control what program will be called with the png file as its command line argument.
    * On OS-X open will launch the default viewer (usually preview)
    * Set program to none to turn off this feature
    * @param program program to open png
    * @return
    */
  def setOpenProgram(program: String): Annotation = {
    Annotation(CircuitTopName, classOf[VisualizerTransform], s"${Visualizer.OpenProgramString}=$program")
  }

  def unapply(a: Annotation): Option[(Named, String)] = a match {
    case Annotation(named, t, value) if t == classOf[VisualizerTransform] =>
      Some((named, value))
    case _ => None
  }
}

/**
  * Add this trait to a module to allow user to specify that the module or a submodule should be
  * rendered
  */
trait VisualizerAnnotator {
  self: chisel3.Module =>

  /**
    * Use this to create an firrtl annotation,
    * @param component  The module to start rendering
    * @param depth      Stop rendering after this many sub-modules have been descended. 0 just does IOs of target
    *                   1 does all components of current target, plus IOs of submodules referenced,
    *                   and so forth.
    * @return
    */
  def visualize(component: chisel3.Module, depth: Int = 0): Unit = {
    annotate(ChiselAnnotation(component, classOf[VisualizerTransform], s"${Visualizer.DepthString}=$depth"))
  }
  /**
    * Use this to set the program to convert the dot file to a png.  dot and fdp seem to work well, others
    * might too.  Default is dot
    * @param program program to create png
    * @return
    */
  def setDotProgram(program: String): Unit = {
    annotate(ChiselAnnotation(self, classOf[VisualizerTransform], s"${Visualizer.DotProgramString}=$program"))
  }
  /**
    * Use this control what program will be called with the png file as its command line argument.
    * On OS-X open will launch the default viewer (usually preview)
    * Set program to none to turn off this feature
    * @param program program to open png
    * @return
    */
  def setOpenProgram(program: String): Unit = {
    annotate(ChiselAnnotation(self, classOf[VisualizerTransform], s"${Visualizer.OpenProgramString}=$program"))
  }
}

/**
  * Annotations specify where to start rendering.  Currently the first encountered module that matches an annotation
  * will start the rendering, rendering continues per the depth specified in the annotation.
  * This pass is intermixed with other low to low transforms, it is not treated as a separate
  * emit, so if so annotated it will run with every firrtl compilation.
  *
  * @param annotations  where to start rendering
  */
//noinspection ScalaStyle
class VisualizerPass(val annotations: Seq[Annotation]) extends Pass {
  def run (c:Circuit) : Circuit = {
    val nameToNode: mutable.HashMap[String, DotNode] = new mutable.HashMap()

    val printFile = new PrintWriter(new java.io.File(s"${c.main}.dot"))
    def pl(s: String): Unit = {
      printFile.println(s.split("\n").mkString("\n"))
    }

    /**
      * finds the specified module name in the circuit
      *
      * @param moduleName name to find
      * @param circuit circuit being analyzed
      * @return the circuit, exception occurs in not found
      */
    def findModule(moduleName: String, circuit: Circuit): DefModule = {
      circuit.modules.find(module => module.name == moduleName) match {
        case Some(module: firrtl.ir.Module) =>
          module
        case Some(externalModule: DefModule) =>
          externalModule
        case _ =>
          throw new Exception(s"Could not find top level module in $moduleName")
      }
    }

    /**
      * If rendering started, construct a graph inside moduleNode
      * @param modulePrefix the path to this node
      * @param myModule     the firrtl module currently being parsed
      * @param moduleNode   a node renderable to dot notation constructed from myModule
      * @return
      */
    def processModule(
                       modulePrefix: String,
                       myModule: DefModule,
                       moduleNode: ModuleNode,
                       scope: Scope = Scope()
                     ): DotNode = {
      /**
        * Half the battle here is matching references between firrtl full name for an element and
        * dot's reference to a connect-able module
        * Following functions compute the two kinds of name
        */

      /** get firrtl's version, usually has dot's as separators
        * @param name components name
        * @return
        */
      def getFirrtlName(name: String): String = {
        if(modulePrefix.isEmpty) name else modulePrefix + "." + name
      }

      def expand(name: String): String = {
        s"${moduleNode.absoluteName}_$name".replaceAll("""\.""", "_")
      }

      def processPrimOp(primOp: DoPrim): String = {
        def addBinOpNode(symbol: String): String = {
          val opNode = BinaryOpNode(symbol, Some(moduleNode))
          moduleNode += opNode
          moduleNode.connect(opNode.in1, processExpression(primOp.args.head))
          moduleNode.connect(opNode.in2, processExpression(primOp.args.tail.head))
          opNode.asRhs
        }

        def addUnaryOpNode(symbol: String): String = {
          val opNode = UnaryOpNode(symbol, Some(moduleNode))
          moduleNode += opNode
          moduleNode.connect(opNode.in1, processExpression(primOp.args.head))
          opNode.asRhs
        }

        def addOneArgOneParamOpNode(symbol: String): String = {
          val opNode = OneArgOneParamOpNode(symbol, Some(moduleNode), primOp.consts.head)
          moduleNode += opNode
          moduleNode.connect(opNode.in1, processExpression(primOp.args.head))
          opNode.asRhs
        }

        primOp.op match {
          case Add => addBinOpNode("add")
          case Sub => addBinOpNode("sub")
          case Mul => addBinOpNode("mul")
          case Div => addBinOpNode("div")
          case Rem => addBinOpNode("rem")

          case Eq  => addBinOpNode("eq")
          case Neq => addBinOpNode("neq")
          case Lt  => addBinOpNode("lt")
          case Leq => addBinOpNode("lte")
          case Gt  => addBinOpNode("gt")
          case Geq => addBinOpNode("gte")

          case Pad => addUnaryOpNode("pad")

          case AsUInt => addUnaryOpNode("asUInt")
          case AsSInt => addUnaryOpNode("asSInt")

          case Shl => addOneArgOneParamOpNode("shl")
          case Shr => addOneArgOneParamOpNode("shr")

          case Dshl => addBinOpNode("dshl")
          case Dshr => addBinOpNode("dshr")

          case Cvt => addUnaryOpNode("cvt")
          case Neg => addUnaryOpNode("neg")
          case Not => addUnaryOpNode("not")

          case And => addBinOpNode("and")
          case Or  => addBinOpNode("or")
          case Xor => addBinOpNode("xor")

          case Andr => addUnaryOpNode("andr")
          case Orr  => addUnaryOpNode("orr")
          case Xorr => addUnaryOpNode("xorr")

          case Cat => addBinOpNode("cat")

          case Bits =>
            val opNode = OneArgTwoParamOpNode("bits", Some(moduleNode), primOp.consts.head, primOp.consts.tail.head)
            moduleNode += opNode
            moduleNode.connect(opNode.in1, processExpression(primOp.args.head))
            opNode.asRhs


          case Head => addOneArgOneParamOpNode("head")
          case Tail => addOneArgOneParamOpNode("tail")

          case _ =>
            "dummy"
        }
      }

      def processExpression(expression: firrtl.ir.Expression): String = {
        def resolveRef(firrtlName: String, dotName: String): String = {
          nameToNode.get(firrtlName) match {
            case Some(node) =>
              node.asRhs
            case _ => dotName
          }
        }
        val result = expression match {
          case mux: firrtl.ir.Mux =>
            val muxNode = MuxNode(s"mux_${mux.hashCode().abs}", Some(moduleNode))
            moduleNode += muxNode
            moduleNode.connect(muxNode.select, processExpression(mux.cond))
            moduleNode.connect(muxNode.in1, processExpression(mux.tval))
            moduleNode.connect(muxNode.in2, processExpression(mux.fval))
            muxNode.asRhs
          case WRef(name, _, _, _) =>
            resolveRef(getFirrtlName(name), expand(name))
          case Reference(name, _) =>
            resolveRef(getFirrtlName(name), expand(name))
          case subfield: WSubField =>
            resolveRef(getFirrtlName(subfield.serialize), expand(subfield.serialize))
          case subindex: WSubIndex =>
            resolveRef(getFirrtlName(subindex.serialize), expand(subindex.serialize))
          case validIf : ValidIf =>
            val validIfNode = ValidIfNode(s"validif_${validIf.hashCode().abs}", Some(moduleNode))
            moduleNode += validIfNode
            moduleNode.connect(validIfNode.select, processExpression(validIf.cond))
            moduleNode.connect(validIfNode.in1, processExpression(validIf.value))
            validIfNode.asRhs
          case primOp: DoPrim =>
            processPrimOp(primOp)
          case c: UIntLiteral =>
            val uInt = LiteralNode(s"lit${PrimOpNode.hash}", c.value, Some(moduleNode))
            moduleNode += uInt
            uInt.absoluteName
          case c: SIntLiteral =>
            val uInt = LiteralNode(s"lit${PrimOpNode.hash}", c.value, Some(moduleNode))
            moduleNode += uInt
            uInt.absoluteName
          case other =>
            // throw new Exception(s"renameExpression:error: unhandled expression $expression")
            other.getClass.getName
            ""
        }
        result
      }

      def processPorts(module: DefModule): Unit = {
        def showPorts(dir: firrtl.ir.Direction): Unit = {
          module.ports.foreach {
            case port if port.direction == dir =>
              val portNode = PortNode(port.name, Some(moduleNode))
              nameToNode(getFirrtlName(port.name)) = portNode
              moduleNode += portNode
            case _ => None
          }

        }

        if(scope.doPorts) {
          showPorts(firrtl.ir.Input)
          showPorts(firrtl.ir.Output)
        }
      }

      def processMemory(memory: DefMemory): Unit = {
        val fName = getFirrtlName(memory.name)
        val memNode = MemNode(memory.name, Some(moduleNode), fName, memory, nameToNode)
        moduleNode += memNode
      }

      def processStatement(s: Statement): Unit = {
        s match {
          case block: Block =>
            block.stmts.foreach { subStatement =>
              processStatement(subStatement)
            }
          case con: Connect if scope.doComponents =>
            val (fName, dotName) = con.loc match {
              case WRef(name, _, _, _) => (getFirrtlName(name), expand(name))
              case Reference(name, _) => (getFirrtlName(name), expand(name))
              case subfield: WSubField =>
                (getFirrtlName(subfield.serialize), expand(subfield.serialize))
              case subfield: SubField =>
                (getFirrtlName(s.serialize), expand(subfield.serialize))
              case s: WSubIndex => (getFirrtlName(s.serialize), expand(s.serialize))
              case other =>
                println(s"Found bad connect arg $other")
                ("badName","badName")
            }
            val lhsName = nameToNode.get(fName) match {
              case Some(regNode: RegisterNode) => regNode.in
              case Some(memPort: MemoryPort) => memPort.absoluteName
              case _ => dotName
            }
            moduleNode.connect(lhsName, processExpression(con.expr))

          case WDefInstance(_, instanceName, moduleName, _) =>
            val subModule = findModule(moduleName, c)
            val newPrefix = if(modulePrefix.isEmpty) instanceName else modulePrefix + "." + instanceName
            val subModuleNode = ModuleNode(instanceName, Some(moduleNode))
            moduleNode += subModuleNode

            processModule(newPrefix, subModule, subModuleNode, getScope(moduleName, scope))

          case DefNode(_, name, expression) if scope.doComponents =>
            val fName = getFirrtlName(name)
            val nodeNode = NodeNode(name, Some(moduleNode))
            moduleNode += nodeNode
            nameToNode(fName) = nodeNode
            moduleNode.connect(expand(name), processExpression(expression))
          case DefWire(_, name, _) if scope.doComponents =>
            val fName = getFirrtlName(name)
            val nodeNode = NodeNode(name, Some(moduleNode))
            nameToNode(fName) = nodeNode
          case reg: DefRegister if scope.doComponents =>
            val regNode = RegisterNode(reg.name, Some(moduleNode))
            nameToNode(getFirrtlName(reg.name)) = regNode
            moduleNode += regNode
          case memory: DefMemory if scope.doComponents =>
            processMemory(memory)
          case _ =>
          // let everything else slide
        }
      }

      // println(s"Scope is $scope")

      myModule match {
        case module: firrtl.ir.Module =>
          processPorts(myModule)
          processStatement(module.body)
        case extModule: ExtModule =>
          processPorts(extModule)
        case a =>
          println(s"got a $a")
      }

      moduleNode
    }

    def getScope(moduleName: String, currentScope: Scope = Scope()): Scope = {
      val applicableAnnotation = annotations.find { annotation =>
        annotation.target match {
          case ModuleName(annotationModuleName, _) =>
            annotationModuleName == moduleName
          case CircuitTopName =>
            currentScope.maxDepth >= 0
          case _ =>
            false
        }
      }
      applicableAnnotation match {
        case Some(VisualizerAnnotation(_, maxDepthString)) =>
          val maxDepth = maxDepthString.split("=", 2).last.trim.toInt
          Scope(0, maxDepth)
        case _ =>
          currentScope.descend
      }
    }

    findModule(c.main, c) match {
      case topModule: DefModule =>
        pl(s"digraph ${topModule.name} {")
//        pl(s"graph [splines=ortho];")
        val topModuleNode = ModuleNode(c.main, parentOpt = None)
        processModule("", topModule, topModuleNode, getScope(topModule.name))
        pl(topModuleNode.render)
        pl("}")
      case _ =>
        println(s"could not find top module ${c.main}")
    }

    printFile.close()

    c
  }
}

class VisualizerTransform extends Transform {
  override def inputForm: CircuitForm = LowForm

  override def outputForm: CircuitForm = LowForm

  def show(fileName: String, dotProgram: String = "dot", openProgram: String = "open"): Unit = {
    if(dotProgram != "none") {
      val dotProcessString = s"$dotProgram -Tpng -O $fileName"
      dotProcessString.!!

      if(openProgram != "none") {
        val openProcessString = s"$openProgram $fileName.png"
        openProcessString.!!
      }
    }
  }

  override def execute(state: CircuitState): CircuitState = {
    var dotProgram = "dot"
    var openProgram = "open"

    getMyAnnotations(state) match {
      case Nil => state
      case myAnnotations =>
        val filteredAnnotations = myAnnotations.flatMap {
          case annotation@VisualizerAnnotation(_, value) =>
            if(value.startsWith(Visualizer.DotProgramString)) {
              dotProgram = value.split("=", 2).last.trim
              None
            }
            else if(value.startsWith(Visualizer.OpenProgramString)) {
              openProgram = value.split("=", 2).last.trim
              None
            }
            else {
              Some(annotation)
            }
        }
        new VisualizerPass(filteredAnnotations).run(state.circuit)

        val fileName = s"${state.circuit.main}.dot"

        show(fileName, dotProgram, openProgram)

        state
    }
  }
}

object Visualizer {
  val DepthString       = "Depth"
  val DotProgramString  = "DotProgram"
  val OpenProgramString = "OpenProgram"

  def run(fileName : String, dotProgram : String = "fdp", openProgram: String = "open"): Unit = {
    val sourceFirttl = io.Source.fromFile(fileName).getLines().mkString("\n")

    val ast = Parser.parse(sourceFirttl)
    val annotations = AnnotationMap(
      Seq(
        VisualizerAnnotation(CircuitTopName),
        VisualizerAnnotation.setDotProgram(dotProgram),
        VisualizerAnnotation.setOpenProgram(openProgram)
      )
    )
    val circuitState = CircuitState(ast, LowForm, Some(annotations))

    val transform = new VisualizerTransform

    transform.execute(circuitState)
  }

  //scalastyle:off regex
  def main(args: Array[String]): Unit = {
    args.toList match {
      case fileName :: dotProgram :: openProgram :: Nil =>
        run(fileName, dotProgram, openProgram)
      case fileName :: dotProgram :: Nil =>
        run(fileName, dotProgram)
      case fileName :: Nil =>
        run(fileName)
      case _ =>
        println("Usage: Visualizer <lo-firrtl-file> <dot-program> <open-program>")
        println("       <dot-program> must be one of dot family circo, dot, fdp, neato, osage, sfdp, twopi")
        println("                     default is dot, use none to not produce png")
        println("       <open-program> default is open, this works on os-x, use none to not open")
    }
  }
}

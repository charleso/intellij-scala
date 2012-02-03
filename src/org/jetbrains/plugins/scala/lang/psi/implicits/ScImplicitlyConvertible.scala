package org.jetbrains.plugins.scala
package lang
package psi
package implicits

import api.toplevel.imports.usages.ImportUsed
import caches.CachesUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.psi.util.{PsiTreeUtil, CachedValue, PsiModificationTracker}
import types._
import _root_.scala.collection.Set
import result.TypingContext
import com.intellij.psi._
import collection.mutable.ArrayBuffer
import api.expr.ScExpression
import api.base.patterns.ScBindingPattern
import api.statements._
import api.toplevel.{ScModifierListOwner, ScTypedDefinition}
import api.toplevel.typedef._
import lang.resolve.processor.ImplicitProcessor
import params.{ScClassParameter, ScParameter, ScTypeParam}
import psi.impl.ScalaPsiManager
import api.toplevel.templates.{ScExtendsBlock, ScTemplateBody}
import lang.resolve.{ResolveUtils, StdKinds, ScalaResolveResult}

/**
 * @author ilyas
 *
 * Mix-in implementing functionality to collect [and possibly apply] implicit conversions
 */
//todo: refactor this terrible code
trait ScImplicitlyConvertible extends ScalaPsiElement {
  self: ScExpression =>

  /**
   * Get all implicit types for given expression
   */
  def getImplicitTypes : List[ScType] = {
      implicitMap().map(_._1).toList
  }

  /**
   * returns class which contains function for implicit conversion to type t.
   */
  def getClazzForType(t: ScType): Option[PsiClass] = {
    implicitMap().find(tp => t.equiv(tp._1)) match {
      case Some((_, fun, _)) => {
        fun.getParent match {
          case tb: ScTemplateBody => Some(PsiTreeUtil.getParentOfType(tb, classOf[PsiClass]))
          case _ => None
        }
      }
      case _ => None
    }
  }

  /**
   *  Get all imports used to obtain implicit conversions for given type
   */
  def getImportsForImplicit(t: ScType): Set[ImportUsed] = {
    implicitMap().find(tp => t.equiv(tp._1)).map(s => s._3) match {
      case Some(s) => s
      case None => Set()
    }
  }

  def implicitMap(exp: Option[ScType] = None,
                  fromUnder: Boolean = false,
                  args: Seq[ScType] = Seq.empty,
                  exprType: Option[ScType] = None): Seq[(ScType, PsiNamedElement, Set[ImportUsed])] = {
    import collection.mutable.HashSet
    val buffer = new ArrayBuffer[(ScType, PsiNamedElement, Set[ImportUsed])]
    val seen = new HashSet[PsiNamedElement]
    for (elem <- implicitMapFirstPart(exp, fromUnder, exprType)) {
      if (!seen.contains(elem._2)) {
        seen += elem._2
        buffer += elem
      }
    }
    for (elem <- implicitMapSecondPart(exp, fromUnder, args = args)) {
      if (!seen.contains(elem._2)) {
        seen += elem._2
        buffer += elem
      }
    }
    buffer.toSeq
  }

  def implicitMapFirstPart(exp: Option[ScType] = None,
                  fromUnder: Boolean = false,
                  exprType: Option[ScType] = None): Seq[(ScType, PsiNamedElement, Set[ImportUsed])] = {
    type Data = (Option[ScType], Boolean, Option[ScType])
    val data = (exp, fromUnder, exprType)

    CachesUtil.getMappedWithRecursionPreventing(this, data, CachesUtil.IMPLICIT_MAP1_KEY,
      (expr: ScExpression, data: Data) => buildImplicitMap(data._1, data._2, (true, false), Seq.empty, data._3), Seq.empty,
      PsiModificationTracker.MODIFICATION_COUNT)
  }

  def implicitMapSecondPart(exp: Option[ScType] = None,
                            fromUnder: Boolean = false,
                            args: Seq[ScType] = Seq.empty,
                            exprType: Option[ScType] = None): Seq[(ScType, PsiNamedElement, Set[ImportUsed])] = {
    type Data = (Option[ScType], Boolean, Seq[ScType], Option[ScType])
    val data = (exp, fromUnder, args, exprType)

    CachesUtil.getMappedWithRecursionPreventing(this, data, CachesUtil.IMPLICIT_MAP2_KEY,
      (expr: ScExpression, data: Data) => buildImplicitMap(data._1, data._2, (false, true), data._3, data._4), Seq.empty,
      PsiModificationTracker.MODIFICATION_COUNT)
  }

  private def buildImplicitMap(exp: Option[ScType],
                               fromUnder: Boolean = false,
                               part: (Boolean, Boolean),
                               args: Seq[ScType] = Seq.empty,
                               exprType: Option[ScType] = None): Seq[(ScType, PsiNamedElement, Set[ImportUsed])] = {
    val typez: ScType = exprType.getOrElse(
      getTypeWithoutImplicits(TypingContext.empty, fromUnderscore = fromUnder).getOrElse(return Seq.empty)
    )

    val buffer = new ArrayBuffer[(ScalaResolveResult, ScType, ScType, ScSubstitutor, ScUndefinedSubstitutor)]
    if (part._1) {
      buffer ++= buildSimpleImplicitMap(fromUnder)
    }
    if (part._2) {
      val processor = new CollectImplicitsProcessor(true)
      val expandedType: ScType = exp match {
        case Some(expected) =>
          new ScFunctionType(expected, Seq(typez) ++ args)(getProject, getResolveScope)
        case None if !args.isEmpty => ScTupleType(Seq(typez) ++ args)(getProject, getResolveScope)
        case None => typez
      }
      for (obj <- ScalaPsiUtil.collectImplicitObjects(expandedType, this)) {
        processor.processType(obj, this, ResolveState.initial())
      }
      for ((pass, resolveResult, tp, rt, newSubst, subst) <- processor.candidatesS.map(forMap(_, typez)) if pass) {
        buffer += ((resolveResult, tp, rt, newSubst, subst))
      }
    }

    val result = new ArrayBuffer[(ScType, PsiNamedElement, Set[ImportUsed])]

    buffer.foreach{case tuple => {
      val (r, tp, retTp, newSubst, uSubst) = tuple

      r.element match {
        case f: ScFunction if f.hasTypeParameters => {
          uSubst.getSubstitutor match {
            case Some(substitutor) =>
              exp match {
                case Some(expected) =>
                  val additionalUSubst = Conformance.undefinedSubst(expected, newSubst.subst(retTp))
                  (uSubst + additionalUSubst).getSubstitutor match {
                    case Some(innerSubst) =>
                      result += ((innerSubst.subst(retTp), r.element, r.importsUsed))
                    case None =>
                      result += ((substitutor.subst(retTp), r.element, r.importsUsed))
                  }
                case None =>
                  result += ((substitutor.subst(retTp), r.element, r.importsUsed))
              }
            case _ => (false, r, tp, retTp)
          }
        }
        case _ =>
          result += ((retTp, r.element, r.importsUsed))
      }
    }}

    result.toSeq
  }

  private def buildSimpleImplicitMap(fromUnder: Boolean): ArrayBuffer[(ScalaResolveResult, ScType,
                                                                    ScType, ScSubstitutor, ScUndefinedSubstitutor)] = {
    if (fromUnder) return buildSimpleImplicitMapInner(fromUnder)
    import org.jetbrains.plugins.scala.caches.CachesUtil._
    get(this, IMPLICIT_SIMPLE_MAP_KEY,
      new MyProvider[ScImplicitlyConvertible, ArrayBuffer[(ScalaResolveResult, ScType,
                                                          ScType, ScSubstitutor, ScUndefinedSubstitutor)]](this, _ => {
        buildSimpleImplicitMapInner(fromUnder /* false */)
      })(PsiModificationTracker.MODIFICATION_COUNT))
  }

  private def buildSimpleImplicitMapInner(fromUnder: Boolean): ArrayBuffer[(ScalaResolveResult, ScType,
                                                                    ScType, ScSubstitutor, ScUndefinedSubstitutor)] = {
    val typez: ScType = getTypeWithoutImplicits(TypingContext.empty, fromUnderscore = fromUnder).getOrElse(null)
    if (typez == null) return ArrayBuffer.empty
    val processor = new CollectImplicitsProcessor(false)

    // Collect implicit conversions from bottom to up
    def treeWalkUp(place: PsiElement, lastParent: PsiElement) {
      if (place == null) return
      if (!place.processDeclarations(processor,
        ResolveState.initial,
        lastParent, this)) return
      place match {
        case (_: ScTemplateBody | _: ScExtendsBlock) => //template body and inherited members are at the same level
        case _ => if (!processor.changedLevel) return
      }
      treeWalkUp(place.getContext, place)
    }
    treeWalkUp(this, null)

    val result = new ArrayBuffer[(ScalaResolveResult, ScType, ScType, ScSubstitutor, ScUndefinedSubstitutor)]
    if (typez == Nothing) return result
    if (typez.isInstanceOf[ScUndefinedType]) return result

    val sigsFound = processor.candidatesS.map(forMap(_, typez))


    for ((pass, resolveResult, tp, rt, newSubst, subst) <- sigsFound if pass) {
      result += ((resolveResult, tp, rt, newSubst, subst))
    }

    result
  }

  def forMap(r: ScalaResolveResult, typez: ScType): (Boolean, ScalaResolveResult, ScType, ScType, ScSubstitutor, ScUndefinedSubstitutor) = {
    if (!PsiTreeUtil.isContextAncestor(ScalaPsiUtil.nameContext(r.element), this, false)) { //to prevent infinite recursion
      ProgressManager.checkCanceled()
      lazy val funType: ScParameterizedType = {
        val fun = "scala.Function1"
        val funClass = ScalaPsiManager.instance(getProject).getCachedClass(fun, this.getResolveScope, ScalaPsiManager.ClassCategory.TYPE)
        funClass match {
          case cl: ScTrait => new ScParameterizedType(ScType.designator(funClass), cl.typeParameters.map(tp =>
            new ScUndefinedType(new ScTypeParameterType(tp, ScSubstitutor.empty), 1)))
        }
      }
      val subst = r.substitutor
      val (tp: ScType, retTp: ScType) = r.element match {
        case f: ScFunction if f.paramClauses.clauses.length > 0 => {
           val params = f.paramClauses.clauses.apply(0).parameters
          (subst.subst(params.apply(0).getType(TypingContext.empty).getOrNothing),
           subst.subst(f.returnType.getOrNothing))
        }
        case f: ScFunction => {
          Conformance.undefinedSubst(funType, subst.subst(f.returnType.get)).getSubstitutor match {
            case Some(innerSubst) => (innerSubst.subst(funType.typeArgs.apply(0)), innerSubst.subst(funType.typeArgs.apply(1)))
            case _ => (Nothing, Nothing)
          }
        }
        case b: ScBindingPattern => {
          Conformance.undefinedSubst(funType, subst.subst(b.getType(TypingContext.empty).get)).getSubstitutor match {
            case Some(innerSubst) => (innerSubst.subst(funType.typeArgs.apply(0)), innerSubst.subst(funType.typeArgs.apply(1)))
            case _ => (Nothing, Nothing)
          }
        }
        case param: ScParameter => {
          // View Bounds and Context Bounds are processed as parameters.
          Conformance.undefinedSubst(funType, subst.subst(param.getType(TypingContext.empty).get)).
                  getSubstitutor match {
            case Some(innerSubst) => (innerSubst.subst(funType.typeArgs.apply(0)), innerSubst.subst(funType.typeArgs.apply(1)))
            case _ => (Nothing, Nothing)
          }
        }
        case obj: ScObject => {
          Conformance.undefinedSubst(funType, subst.subst(obj.getType(TypingContext.empty).get)).
                  getSubstitutor match {
            case Some(innerSubst) => (innerSubst.subst(funType.typeArgs.apply(0)), innerSubst.subst(funType.typeArgs.apply(1)))
            case _ => (Nothing, Nothing)
          }
        }
      }
      val newSubst = r.element match {
        case f: ScFunction => inferMethodTypesArgs(f, r.substitutor)
        case _ => ScSubstitutor.empty
      }
      if (!typez.weakConforms(newSubst.subst(tp))) {
        (false, r, tp, retTp, null: ScSubstitutor, null: ScUndefinedSubstitutor)
      } else {
        r.element match {
          case f: ScFunction if f.hasTypeParameters => {
            var uSubst = Conformance.undefinedSubst(newSubst.subst(tp), typez)
            //todo: improve it to make it right
            val removeTParametersSubst = new ScSubstitutor(f.typeParameters.map((param: ScTypeParam) => {
              ((param.getName, ScalaPsiUtil.getPsiElementId(param)), ScExistentialArgument("_", List.empty, Nothing, Any))
            }).toMap, Map.empty, None)
            for (tParam <- f.typeParameters) {
              val lowerType: ScType = tParam.lowerBound.getOrNothing
              if (lowerType != Nothing) uSubst = uSubst.addLower((tParam.getName, ScalaPsiUtil.getPsiElementId(tParam)),
                removeTParametersSubst.subst(subst.subst(lowerType)))
              val upperType: ScType = tParam.upperBound.getOrAny
              if (upperType != Any) uSubst = uSubst.addUpper((tParam.getName, ScalaPsiUtil.getPsiElementId(tParam)),
                removeTParametersSubst.subst(subst.subst(upperType)))
            }
            //todo: pass implicit parameters
            (true, r, tp, retTp, newSubst, uSubst)
          }
          case _ =>
            (true, r, tp, retTp, newSubst, null: ScUndefinedSubstitutor)
        }
      } //possible true
    } else {
      (false, r, null: ScType, null: ScType, null: ScSubstitutor, null: ScUndefinedSubstitutor)
    }
  }


  class CollectImplicitsProcessor(withoutPrecedence: Boolean) extends ImplicitProcessor(StdKinds.refExprLastRef, withoutPrecedence) {
    //can be null (in Unit tests or without library)
    private val funType: ScParameterizedType = {
      val funClass: PsiClass = ScalaPsiManager.instance(getProject).getCachedClass(getResolveScope, "scala.Function1")
      funClass match {
        case cl: ScTrait => new ScParameterizedType(ScType.designator(funClass), cl.typeParameters.map(tp =>
          new ScUndefinedType(new ScTypeParameterType(tp, ScSubstitutor.empty))))
        case _ => null
      }
    }

    protected def getPlace: PsiElement = ScImplicitlyConvertible.this

    def execute(element: PsiElement, state: ResolveState): Boolean = {
      val subst: ScSubstitutor = getSubst(state)

      element match {
        case named: PsiNamedElement if kindMatches(element) => named match {
          //there is special case for Predef.conforms method
          case f: ScFunction if f.hasModifierProperty("implicit") && !isConformsMethod(f) => {
            if (!ResolveUtils.isAccessible(f, getPlace)) return true
            val clauses = f.paramClauses.clauses
            //filtered cases
            if (clauses.length > 2 || (clauses.length == 2 && !clauses(1).isImplicit)) return true
            if (clauses.length == 0) {
              val rt = subst.subst(f.returnType.getOrElse(return true))
              if (funType == null || !rt.conforms(funType)) return true
            } else if (clauses(0).parameters.length != 1 || clauses(0).isImplicit) return true
            addResult(new ScalaResolveResult(f, subst, getImports(state)))
          }
          case b: ScBindingPattern => {
            ScalaPsiUtil.nameContext(b) match {
              case d: ScDeclaredElementsHolder if (d.isInstanceOf[ScValue] || d.isInstanceOf[ScVariable]) &&
                      d.asInstanceOf[ScModifierListOwner].hasModifierProperty("implicit") => {
                if (!ResolveUtils.isAccessible(d.asInstanceOf[ScMember], getPlace)) return true
                val tp = subst.subst(b.getType(TypingContext.empty).getOrElse(return true))
                if (funType == null || !tp.conforms(funType)) return true
                addResult(new ScalaResolveResult(b, subst, getImports(state)))
              }
              case _ => return true
            }
          }
          case param: ScParameter if param.isImplicitParameter => {
            param match {
              case c: ScClassParameter =>
                if (!ResolveUtils.isAccessible(c, getPlace)) return true
              case _ =>
            }
            val tp = subst.subst(param.getType(TypingContext.empty).getOrElse(return true))
            if (funType == null || !tp.conforms(funType)) return true
            addResult(new ScalaResolveResult(param, subst, getImports(state)))
          }
          case obj: ScObject if obj.hasModifierProperty("implicit") => {
            if (!ResolveUtils.isAccessible(obj, getPlace)) return true
            val tp = subst.subst(obj.getType(TypingContext.empty).getOrElse(return true))
            if (funType == null || !tp.conforms(funType)) return true
            addResult(new ScalaResolveResult(obj, subst, getImports(state)))
          }
          case _ =>
        }
        case _ =>
      }
      true
    }
  }

  private def isConformsMethod(f: ScFunction): Boolean = {
    f.name == "conforms" && Option(f.getContainingClass).flatMap(cls => Option(cls.getQualifiedName)).exists(_ == "scala.Predef")
  }

  /**
   * Pick all type parameters by method maps them to the appropriate type arguments.
   */
  def inferMethodTypesArgs(fun: ScFunction, classSubst: ScSubstitutor) = {
    fun.typeParameters.foldLeft(ScSubstitutor.empty) {
      (subst, tp) => subst.bindT((tp.getName, ScalaPsiUtil.getPsiElementId(tp)),
        ScUndefinedType(new ScTypeParameterType(tp: ScTypeParam, classSubst)))
    }
  }
}

object ScImplicitlyConvertible {
  val IMPLICIT_RESOLUTION_KEY: Key[PsiClass] = Key.create("implicit.resolution.key")
  val IMPLICIT_CONVERSIONS_KEY: Key[CachedValue[collection.Map[ScType, Set[(ScFunctionDefinition, Set[ImportUsed])]]]] = Key.create("implicit.conversions.key")

  case class Implicit(tp: ScType, fun: ScTypedDefinition, importsUsed: Set[ImportUsed])
}

package org.jetbrains.plugins.scala.lang.psi.implicits

import org.jetbrains.plugins.scala.lang.psi.types._
import org.jetbrains.plugins.scala.lang.resolve._
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScMember, ScTypeDefinition, ScTemplateDefinition}
import processor.{MostSpecificUtil, BaseProcessor}
import result.{Success, TypingContext}
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScTypeParam
import collection.mutable.ArrayBuffer
import com.intellij.psi._
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.{ScTypedDefinition, ScNamedElement}
import org.jetbrains.plugins.scala.lang.psi.api.statements._
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns.{ScReferencePattern, ScBindingPattern}
import util.PsiTreeUtil
import org.jetbrains.plugins.scala.caches.CachesUtil

/**
 * User: Alexander Podkhalyuzin
 * Date: 23.11.2009
 */

class ImplicitParametersCollector(place: PsiElement, tp: ScType) {
  def collectImplicitClasses(tp: ScType): Seq[(PsiClass, ScSubstitutor)] = {
    tp match {
      case ScCompoundType(comps, _, _, _) => {
        comps.flatMap(collectImplicitClasses(_))
      }
      case p@ScParameterizedType(des, args) => {
        (ScType.extractClassType(p) match {
          case Some(pair) => Seq(pair)
          case _ => Seq.empty
        }) ++ args.flatMap(collectImplicitClasses(_))
      }
      case j: JavaArrayType => {
        val parameterizedType = j.getParameterizedType(place.getProject, place.getResolveScope)
        collectImplicitClasses(parameterizedType.getOrElse(return Seq.empty))
      }
      case singl@ScSingletonType(path) => collectImplicitClasses(singl.pathType)
      case _=> {
        ScType.extractClassType(tp) match {
          case Some(pair) => Seq(pair)
          case _ => Seq.empty
        }
      }
    }
  }

  def collect: Seq[ScalaResolveResult] = {
    val processor = new ImplicitParametersProcessor
    def treeWalkUp(placeForTreeWalkUp: PsiElement, lastParent: PsiElement) {
      placeForTreeWalkUp match {
        case null =>
        case p => {
          if (!p.processDeclarations(processor,
            ResolveState.initial(),
            lastParent, place)) return
          treeWalkUp(placeForTreeWalkUp.getContext, placeForTreeWalkUp)
        }
      }
    }
    treeWalkUp(place, null) //collecting all references from scope
    for ((clazz, subst) <- collectImplicitClasses(tp)) {
      clazz.processDeclarations(processor, ResolveState.initial.put(ScSubstitutor.key, subst), null, place)
      clazz match {
        case td: ScTemplateDefinition => ScalaPsiUtil.getCompanionModule(td) match {
          case Some(td: ScTypeDefinition) => {
            td.processDeclarations(processor, ResolveState.initial, null, place)
          }
          case _ =>
        }
        case _ =>
      }
    }

    return processor.candidates.toSeq
  }

  class ImplicitParametersProcessor extends BaseProcessor(StdKinds.methodRef) {
    def execute(element: PsiElement, state: ResolveState): Boolean = {
      if (!kindMatches(element)) return true
      val named = element.asInstanceOf[PsiNamedElement]
      val subst = getSubst(state)
      named match {
        case patt: ScBindingPattern => {
          val memb = ScalaPsiUtil.getContextOfType(patt, true, classOf[ScValue], classOf[ScVariable])
          memb match {
            case memb: ScMember if memb.hasModifierProperty("implicit") => candidatesSet += new ScalaResolveResult(named, subst, getImports(state))
            case _ =>
          }
        }
        case function: ScFunction if function.hasModifierProperty("implicit") => {
          candidatesSet += new ScalaResolveResult(named, subst.followed(inferMethodTypesArgs(function, subst)), getImports(state))
        }
        case _ =>
      }
      true
    }

    override def candidates[T >: ScalaResolveResult: ClassManifest]: Array[T] = {
      def forFilter(c: ScalaResolveResult): Boolean = {
        val subst = c.substitutor
        var searches = c.element.getUserData(CachesUtil.IMPLICIT_PARAM_TYPES_KEY)
        if (searches != null && searches.find(_.equiv(tp)) == None)
          c.element.putUserData(CachesUtil.IMPLICIT_PARAM_TYPES_KEY, tp :: searches)
        else if (searches == null)
          c.element.putUserData(CachesUtil.IMPLICIT_PARAM_TYPES_KEY, List(tp))
        else return false
        try {
          c.element match {
            case patt: ScBindingPattern => {
              patt.getType(TypingContext.empty) match {
                case Success(pattType: ScType, _) => subst.subst(pattType) conforms tp
                case _ => false
              }
            }
            case fun: ScFunction => {
              val oneImplicit = fun.paramClauses.clauses.length == 1 && fun.paramClauses.clauses.apply(0).isImplicit
              fun.getType(TypingContext.empty) match {
                case Success(funType: ScType, _) => {
                  if (subst.subst(funType) conforms tp) return true
                  else {
                    subst.subst(funType) match {
                      case ScFunctionType(ret, params) if params.length == 0 || oneImplicit => ret conforms tp
                      case _ => false
                    }
                  }
                }
                case _ => false
              }
            }
            case _ => false
          }
        }
        finally {
          searches = c.element.getUserData(CachesUtil.IMPLICIT_PARAM_TYPES_KEY)
          if (searches != null && searches.length > 0)
            c.element.putUserData(CachesUtil.IMPLICIT_PARAM_TYPES_KEY, searches.tail)
          else {} //do nothing
        }
      }
      def forMap(c: ScalaResolveResult): ScalaResolveResult = {
        val subst = c.substitutor
        var searches = c.element.getUserData(CachesUtil.IMPLICIT_PARAM_TYPES_KEY)
        if (searches != null && searches.find(_.equiv(tp)) == None)
          c.element.putUserData(CachesUtil.IMPLICIT_PARAM_TYPES_KEY, tp :: searches)
        else if (searches == null)
          c.element.putUserData(CachesUtil.IMPLICIT_PARAM_TYPES_KEY, List(tp))
        else {}
        try {
          c.element match {
            case fun: ScFunction if fun.typeParameters.length > 0 => {
              val funType = fun.getType(TypingContext.empty).get
              val undefSubst = {
                if (subst.subst(funType) conforms tp) Conformance.undefinedSubst(tp, subst.subst(funType))
                else {
                  subst.subst(funType) match {
                    case ScFunctionType(ret, params) => Conformance.undefinedSubst(tp, ret)
                  }
                }
              }
              undefSubst.getSubstitutor match {
                case Some(s: ScSubstitutor) => c.copy(subst.followed(s))
                case _ => c
              }
            }
            case _ => c
          }
        }
        finally {
          searches = c.element.getUserData(CachesUtil.IMPLICIT_PARAM_TYPES_KEY)
          if (searches != null && searches.length > 0)
            c.element.putUserData(CachesUtil.IMPLICIT_PARAM_TYPES_KEY, searches.tail)
          else {} //do nothing
        }
      }
      val applicable: Array[ScalaResolveResult] = candidatesSet.toArray.filter(forFilter(_)).map(forMap(_))
      new MostSpecificUtil(place, 1).mostSpecificForResolveResult(applicable.toSet) match {
        case Some(r) => return Array(r)
        case _ => applicable.toArray[T]
      }
    }


    /**
     Pick all type parameters by method maps them to the appropriate type arguments, if they are
     */
    def inferMethodTypesArgs(fun: ScFunction, classSubst: ScSubstitutor) = {
      fun.typeParameters.foldLeft(ScSubstitutor.empty) {
        (subst, tp) => subst.bindT((tp.getName, ScalaPsiUtil.getPsiElementId(tp)), new ScUndefinedType(new ScTypeParameterType(tp: ScTypeParam, classSubst), 1))
      }
    }
  }
}


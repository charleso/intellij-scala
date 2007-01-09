package org.jetbrains.plugins.scala.lang.psi.impl.expressions {
/**
* @author Ilya Sergey
*/
import com.intellij.lang.ASTNode
import com.intellij.psi._

import org.jetbrains.plugins.scala.lang.psi._

  case class ScInfixExprImpl( node : ASTNode ) extends ScalaPsiElementImpl(node) {
      override def toString: String = "Infix expression"
      def getType() : PsiType = null
  }

  case class ScPrefixExprImpl( node : ASTNode ) extends ScalaPsiElementImpl(node) {
      override def toString: String = "Prefix expression"
      def getType() : PsiType = null
  }

  case class ScPostfixExprImpl( node : ASTNode ) extends ScalaPsiElementImpl(node) {
      override def toString: String = "Postfix expression"
      def getType() : PsiType = null
  }
  
}
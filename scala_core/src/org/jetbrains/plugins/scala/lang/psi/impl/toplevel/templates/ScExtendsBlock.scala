package org.jetbrains.plugins.scala.lang.psi.impl.toplevel.templates

import com.intellij.psi.PsiElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.lang.ASTNode

import org.jetbrains.plugins.scala.lang.parser.ScalaElementTypes
import org.jetbrains.plugins.scala.lang.lexer._
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiElementImpl
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.annotations._
import org.jetbrains.plugins.scala.lang.parser.ScalaElementTypes

import org.jetbrains.plugins.scala.icons.Icons

import org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates._

/** 
* @author Alexander Podkhalyuzin
* Date: 20.02.2008
*/

class ScExtendsBlockImpl(node: ASTNode) extends ScalaPsiElementImpl(node) with ScExtendsBlock{

  override def toString: String = "ExtendsBlock"

  def templateBody: Option[ScTemplateBody] = findChild(classOf[ScTemplateBody])

  def empty: Boolean = {
    return getNode.getFirstChildNode == null
  }
}
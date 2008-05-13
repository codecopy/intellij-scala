package org.jetbrains.plugins.scala.lang.psi.api.statements

import org.jetbrains.plugins.scala.lang.psi.ScalaPsiElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef._
import com.intellij.psi._

/**
* @author Alexander Podkhalyuzin
* Date: 08.04.2008
*/

trait ScValue extends ScalaPsiElement with ScMember {
  def getIdentifierNodes: Array[PsiElement]
}
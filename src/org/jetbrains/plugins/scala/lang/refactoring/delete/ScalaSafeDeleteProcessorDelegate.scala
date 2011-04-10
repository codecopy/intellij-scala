package org.jetbrains.plugins.scala
package lang
package refactoring
package delete

import com.intellij.usageView.UsageInfo
import com.intellij.refactoring.safeDelete.{NonCodeUsageSearchInfo, JavaSafeDeleteProcessor, SafeDeleteProcessorDelegate}
import com.intellij.refactoring.safeDelete.usageInfo.SafeDeleteReferenceJavaDeleteUsageInfo
import com.intellij.codeInsight.daemon.impl.quickfix.RemoveUnusedVariableFix
import com.intellij.psi.util.{PsiTreeUtil, PsiUtil}
import com.intellij.psi._
import java.util.{ArrayList, List}
import search.searches.ReferencesSearch
import org.jetbrains.annotations.Nullable
import com.intellij.openapi.util.Condition
import SafeDeleteProcessorUtil._
import collection.JavaConversions._
import java.util.{List => JList, ArrayList => JArrayList}
import psi.api.statements.params.ScParameter
import psi.api.statements.ScFunction
import psi.api.toplevel.ScTypedDefinition
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.scala.extensions._
import scala.collection.JavaConversions._
import psi.api.toplevel.imports.ScImportStmt
import psi.api.base.ScStableCodeReferenceElement
import psi.api.toplevel.typedef.{ScObject, ScTypeDefinition}

class ScalaSafeDeleteProcessorDelegate extends JavaSafeDeleteProcessor {
  override def handlesElement(element: PsiElement) =
    element.containingScalaFile.isDefined && super.handlesElement(element)

  @Nullable
  override def findUsages(element: PsiElement, allElementsToDelete: Array[PsiElement], usages: JList[UsageInfo]): NonCodeUsageSearchInfo = {
    var insideDeletedCondition: Condition[PsiElement] = getUsageInsideDeletedFilter(allElementsToDelete)

    def usagesForClass(c: PsiClass): JList[UsageInfo] = {
      val infos = new JArrayList[UsageInfo]()
      findClassUsages(c, allElementsToDelete, infos)
      infos.flatMap {
        case x: SafeDeleteReferenceJavaDeleteUsageInfo =>
          // See SCL-3028
          import x._

          val shouldDelete = getElement match {
            case ref: ScStableCodeReferenceElement =>
              val results = ref.multiResolve(false)
              def isSyntheticObject(e: PsiElement) = e.asOptionOf[ScObject].exists(_.isSyntheticObject)
              val nonSyntheticTargets = results.map(_.getElement).filterNot(isSyntheticObject)
              nonSyntheticTargets.toSet subsetOf allElementsToDelete.toSet
            case _ => true
          }
          
          if (shouldDelete) {
            val isInImport = PsiTreeUtil.getParentOfType(getElement, classOf[ScImportStmt]) != null
            if (isInImport) Seq(new SafeDeleteReferenceJavaDeleteUsageInfo(getElement, getReferencedElement, true)) // delete without review
            else Seq(x) // delete with review
          } else Seq()  // don't delete
        case x => Seq(x) // delete with review
      }
    }

    insideDeletedCondition = element match {
      case c: PsiTypeParameter =>
        usages.addAll(usagesForClass(c))
        findTypeParameterExternalUsages(c, usages)
        insideDeletedCondition
      case c: ScTypeDefinition =>
        usages.addAll(usagesForClass(c))
        insideDeletedCondition
      case m: ScFunction => // TODO Scala specific override/implements, extend to vals, members, type aliases etc.
        findMethodUsages(m, allElementsToDelete, usages)
      case p: ScParameter =>
        findParameterUsages(element.asInstanceOf[PsiParameter], usages)
        insideDeletedCondition
      case _ =>
        insideDeletedCondition
    }

    return new NonCodeUsageSearchInfo(insideDeletedCondition, element)
  }

  override def preprocessUsages(project: Project, usages: Array[UsageInfo]) = {
    // Currently this ProcessorDelegate emits SafeDeleteReferenceJavaDeleteUsageInfo, which gets processed by the JavaSafeDeleteProcessor.
    // Right now we rely on that processor to preprocess the usages, so we intentionally don't call super here!

    // TODO Use Scala specific DeleteUsageInfo, and consider them here.
    usages
  }
}
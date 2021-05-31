/*******************************************************************************
* Copyright 2000-2014 JetBrains s.r.o.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*******************************************************************************/
package org.jetbrains.kotlin.ui.editors.codeassist

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.eclipse.jdt.core.IJavaProject
import org.eclipse.jdt.internal.ui.JavaPlugin
import org.eclipse.jdt.internal.ui.JavaPluginImages
import org.eclipse.jdt.internal.ui.viewsupport.JavaElementImageProvider
import org.eclipse.jface.text.IRegion
import org.eclipse.jface.text.ITextViewer
import org.eclipse.jface.text.Region
import org.eclipse.jface.text.contentassist.ContentAssistEvent
import org.eclipse.jface.text.contentassist.ContentAssistant
import org.eclipse.jface.text.contentassist.ICompletionListener
import org.eclipse.jface.text.contentassist.ICompletionProposal
import org.eclipse.jface.text.contentassist.ICompletionProposalSorter
import org.eclipse.jface.text.contentassist.IContentAssistProcessor
import org.eclipse.jface.text.contentassist.IContextInformation
import org.eclipse.jface.text.contentassist.IContextInformationValidator
import org.eclipse.jface.text.templates.TemplateContext
import org.eclipse.jface.text.templates.TemplateProposal
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.eclipse.ui.utils.KotlinImageProvider
import org.jetbrains.kotlin.idea.util.CallTypeAndReceiver
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.ui.editors.KotlinEditor
import org.jetbrains.kotlin.ui.editors.completion.KotlinCompletionUtils
import org.jetbrains.kotlin.ui.editors.templates.KotlinApplicableTemplateContext
import org.jetbrains.kotlin.ui.editors.templates.KotlinDocumentTemplateContext
import org.jetbrains.kotlin.ui.editors.templates.KotlinTemplateManager
import java.util.Comparator
import com.intellij.codeInsight.completion.scope.CompletionElement
import org.jetbrains.kotlin.eclipse.ui.utils.CompletionElementType

abstract class KotlinCompletionProcessor(
    val editor: KotlinEditor,
    private val assistant: ContentAssistant?,
    private val needSorting: Boolean) : IContentAssistProcessor, ICompletionListener {

    companion object {
        private val VALID_PROPOSALS_CHARS = charArrayOf()
        private val VALID_INFO_CHARS = charArrayOf('(', ',')
        fun createKotlinCompletionProcessors(
            editor: KotlinEditor,
            assistant: ContentAssistant? = null,
            needSorting: Boolean = false) = listOf<IContentAssistProcessor>(
            object : KotlinCompletionProcessor(editor, assistant, needSorting) {
                override fun computeProposals(
                    identifierPart: String,
                    psiElement: PsiElement?,
                    simpleNameExpression: KtSimpleNameExpression?,
                    viewer: ITextViewer,
                    offset: Int
                ): List<ICompletionProposal>? =
                    simpleNameExpression?.let {
                        collectCompletionProposals(
                            generateBasicCompletionProposals(identifierPart, simpleNameExpression),
                            identifierPart
                        )
                    }
            },
            object : KotlinCompletionProcessor(editor, assistant, needSorting) {
                override fun computeProposals(
                    identifierPart: String,
                    psiElement: PsiElement?,
                    simpleNameExpression: KtSimpleNameExpression?,
                    viewer: ITextViewer,
                    offset: Int
                ): List<ICompletionProposal>? =
                    simpleNameExpression?.takeIf { identifierPart.isNotBlank() }?.let {
                        generateNonImportedCompletionProposals(identifierPart, simpleNameExpression, editor.javaProject!!)
                    }
            },
            object : KotlinCompletionProcessor(editor, assistant, needSorting) {
                override fun computeProposals(
                    identifierPart: String,
                    psiElement: PsiElement?,
                    simpleNameExpression: KtSimpleNameExpression?,
                    viewer: ITextViewer,
                    offset: Int
                ): List<ICompletionProposal>? =
                    psiElement?.let {
                        generateKeywordProposals(identifierPart, psiElement)
                    }
            },
            object : KotlinCompletionProcessor(editor, assistant, needSorting) {
                override fun computeProposals(
                    identifierPart: String,
                    psiElement: PsiElement?,
                    simpleNameExpression: KtSimpleNameExpression?,
                    viewer: ITextViewer,
                    offset: Int
                ): List<ICompletionProposal>? =
                    psiElement?.let {
                        generateTemplateProposals(psiElement.containingFile, viewer, offset, identifierPart)
                    }
            }
        )
    }
    
    private val kotlinParameterValidator by lazy {
        KotlinParameterListValidator(editor)
    }
    
    override fun computeCompletionProposals(viewer: ITextViewer, offset: Int): Array<ICompletionProposal> {
        if (assistant != null) {
            configureContentAssistant(assistant)
        }
        
        val generatedProposals = generateCompletionProposals(viewer, offset).let {
            if (needSorting) sortProposals(it) else it
        }
        
        return generatedProposals.toTypedArray()
    }

    private fun sortProposals(proposals: List<ICompletionProposal>): List<ICompletionProposal> {
        return proposals.sortedWith(object : Comparator<ICompletionProposal> {
            override fun compare(o1: ICompletionProposal, o2: ICompletionProposal): Int {
                return KotlinCompletionSorter.compare(o1, o2)
            }
        })
    }
    
    private fun configureContentAssistant(contentAssistant: ContentAssistant) {
        contentAssistant.setEmptyMessage("No Default Proposals")
        contentAssistant.setSorter(KotlinCompletionSorter)
    }

    private fun generateCompletionProposals(viewer: ITextViewer, offset: Int): List<ICompletionProposal> {
        val (identifierPart, identifierStart) = getIdentifierInfo(viewer.document, offset)
        val psiElement = KotlinCompletionUtils.getPsiElement(editor, identifierStart)
        val simpleNameExpression = PsiTreeUtil.getParentOfType(psiElement, KtSimpleNameExpression::class.java)

        return computeProposals(identifierPart, psiElement, simpleNameExpression, viewer, offset) ?: emptyList()
    }

    abstract fun computeProposals(
        identifierPart: String,
        psiElement: PsiElement?,
        simpleNameExpression: KtSimpleNameExpression?,
        viewer: ITextViewer,
        offset: Int
    ): List<ICompletionProposal>?

    protected fun generateNonImportedCompletionProposals(
            identifierPart: String,
            expression: KtSimpleNameExpression,
            javaProject: IJavaProject): List<KotlinCompletionProposal> {
        val file = editor.eclipseFile ?: return emptyList()
        val ktFile = editor.parsedFile ?: return emptyList()

        return lookupNonImportedTypes(expression, identifierPart, ktFile, javaProject).map {
            val imageDescriptor = JavaElementImageProvider.getTypeImageDescriptor(false, false, it.modifiers, false)
            val image = JavaPlugin.getImageDescriptorRegistry().get(imageDescriptor)

            KotlinImportCompletionProposal(it, image, file, identifierPart)
        }
    }

    protected fun generateBasicCompletionProposals(identifierPart: String, expression: KtSimpleNameExpression): Collection<DeclarationDescriptor> {
        val file = editor.eclipseFile ?:
            throw IllegalStateException("Failed to retrieve IFile from editor $editor")
        
        val nameFilter: (Name) -> Boolean = { name -> KotlinCompletionUtils.applicableNameFor(identifierPart, name) }
        
        return KotlinCompletionUtils.getReferenceVariants(expression, nameFilter, file, identifierPart)
    }
    
    protected fun collectCompletionProposals(descriptors: Collection<DeclarationDescriptor>, part: String): List<KotlinCompletionProposal> {
        return descriptors.map { descriptor ->
            val completion = descriptor.name.identifier
            val image = KotlinImageProvider.getImage(descriptor)
            val presentableString = DescriptorRenderer.ONLY_NAMES_WITH_SHORT_TYPES.render(descriptor)
            val containmentPresentableString = if (descriptor is ClassDescriptor) {
                val fqName = DescriptorUtils.getFqName(descriptor)
                if (fqName.isRoot) "<root>" else fqName.parent().asString()
            } else {
                null
            }
            
            val proposal = KotlinCompletionProposal(
                                completion,
                                image,
                                presentableString,
                                containmentPresentableString,
                                null,
                                completion,
                                part,
            					CompletionElementType.from(descriptor))
            
            withKotlinInsertHandler(descriptor, proposal, part)
        }
    }
    
    protected fun generateTemplateProposals(
            psiFile: PsiFile, viewer: ITextViewer, offset: Int, identifierPart: String): List<ICompletionProposal> {
        
        val contextTypeIds = KotlinApplicableTemplateContext.getApplicableContextTypeIds(viewer, psiFile, offset - identifierPart.length)
        val region = Region(offset - identifierPart.length, identifierPart.length)
        
        val templateIcon = JavaPluginImages.get(JavaPluginImages.IMG_OBJS_TEMPLATE)
        val templates = KotlinApplicableTemplateContext.getTemplatesByContextTypeIds(contextTypeIds)
        
        return templates
                .filter { it.name.startsWith(identifierPart) }
                .map {
                    val templateContext = createTemplateContext(region, it.contextTypeId)
                    TemplateProposal(it, templateContext, region, templateIcon)
                }
        
    }
    
    private fun createTemplateContext(region: IRegion, contextTypeID: String): TemplateContext {
        return KotlinDocumentTemplateContext(
                KotlinTemplateManager.INSTANCE.contextTypeRegistry.getContextType(contextTypeID),
                editor, region.offset, region.length
        )
    }
    
    protected fun generateKeywordProposals(identifierPart: String, expression: PsiElement): List<KotlinCompletionProposal> {
        val callTypeAndReceiver = if (expression is KtSimpleNameExpression) CallTypeAndReceiver.detect(expression) else null
        
        return arrayListOf<String>().apply {
            KeywordCompletion.complete(expression, identifierPart, true) { keywordProposal ->
                if (!KotlinCompletionUtils.applicableNameFor(identifierPart, keywordProposal)) return@complete
                
                when (keywordProposal) {
                    "break", "continue" -> {
                        if (expression is KtSimpleNameExpression) {
                            addAll(breakOrContinueExpressionItems(expression, keywordProposal))
                        }
                    }                     
                    
                    "class" -> {
                        if (callTypeAndReceiver !is CallTypeAndReceiver.CALLABLE_REFERENCE) {
                            add(keywordProposal)
                        }
                    }
                    
                    "this", "return" -> {
                        if (expression is KtExpression) {
                            add(keywordProposal)
                        }
                    }
                    
                    else -> add(keywordProposal)
                }
            }
        }.map { KotlinKeywordCompletionProposal(it, identifierPart) }
    }

    override fun computeContextInformation(viewer: ITextViewer?, offset: Int): Array<IContextInformation> {
        return KotlinFunctionParameterInfoAssist.computeContextInformation(editor, offset)
    }

    override fun getCompletionProposalAutoActivationCharacters(): CharArray = VALID_PROPOSALS_CHARS

    override fun getContextInformationAutoActivationCharacters(): CharArray = VALID_INFO_CHARS

    override fun getErrorMessage(): String? = ""

    override fun getContextInformationValidator(): IContextInformationValidator = kotlinParameterValidator

    override fun assistSessionStarted(event: ContentAssistEvent?) {
    }

    override fun assistSessionEnded(event: ContentAssistEvent?) {
    }

    override fun selectionChanged(proposal: ICompletionProposal?, smartToggle: Boolean) { }
}

private object KotlinCompletionSorter : ICompletionProposalSorter {
    override fun compare(p1: ICompletionProposal, p2: ICompletionProposal): Int {
		
		// simple and lazy hashing to make relevance more accurate.
        val relevance2 = ((p2.relevance() * p2.typeRelevance()) + (p2.typeRelevance() / 2))
        val relevance1 = ((p1.relevance() * p1.typeRelevance()) + (p1.typeRelevance() / 2))
		return when {
            relevance2 > relevance1 -> 1
            relevance2 < relevance1 -> -1
			else -> p1.sortString().compareTo(p2.sortString(), ignoreCase = true)
		}				
    }
    
    private fun ICompletionProposal.sortString(): String {
        return if (this is KotlinCompletionProposal) this.replacementString else this.displayString
    }
    
    private fun ICompletionProposal.relevance(): Int {
        return if (this is KotlinCompletionProposal) this.getRelevance() else 0
    }
	
	private fun ICompletionProposal.typeRelevance(): Int {
		return when {
			(this is KotlinKeywordCompletionProposal) -> 0
			(this is KotlinImportCompletionProposal) -> 1
			(this is TemplateProposal) -> 2
			(this is KotlinCompletionProposal) -> 3 + this.type.ordinal
			else -> 4
		}
	} 
}
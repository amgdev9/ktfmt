/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.ktfmt

import com.facebook.ktfmt.FormattingOptions.Style.DROPBOX
import com.facebook.ktfmt.FormattingOptions.Style.GOOGLE
import com.facebook.ktfmt.RedundantElementRemover.dropRedundantElements
import com.facebook.ktfmt.debughelpers.printOps
import com.facebook.ktfmt.kdoc.KDocCommentsHelper
import com.facebook.ktfmt.kdoc.indexOfCommentEscapeSequences
import com.google.common.collect.ImmutableList
import com.google.common.collect.Range
import com.google.googlejavaformat.Doc
import com.google.googlejavaformat.DocBuilder
import com.google.googlejavaformat.Newlines
import com.google.googlejavaformat.OpsBuilder
import com.google.googlejavaformat.java.FormatterException
import com.google.googlejavaformat.java.JavaOutput
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtilRt
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.psi.KtImportDirective
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset

const val DEFAULT_MAX_WIDTH: Int = 100

@JvmField
val GOOGLE_FORMAT = FormattingOptions(style = GOOGLE, blockIndent = 2, continuationIndent = 2)

/** A format that attempts to reflect https://kotlinlang.org/docs/coding-conventions.html. */
@JvmField
val KOTLINLANG_FORMAT = FormattingOptions(style = GOOGLE, blockIndent = 4, continuationIndent = 4)

@JvmField
val DROPBOX_FORMAT = FormattingOptions(style = DROPBOX, blockIndent = 4, continuationIndent = 4)

data class FormattingOptions(
    val style: Style = Style.FACEBOOK,

    /** ktfmt breaks lines longer than maxWidth. */
    val maxWidth: Int = DEFAULT_MAX_WIDTH,

    /**
     * blockIndent is the size of the indent used when a new block is opened, in spaces.
     *
     * For example,
     * ```
     * fun f() {
     *   //
     * }
     * ```
     */
    val blockIndent: Int = 2,

    /**
     * continuationIndent is the size of the indent used when a line is broken because it's too
     * long, in spaces.
     *
     * For example,
     * ```
     * val foo = bar(
     *     1)
     * ```
     */
    val continuationIndent: Int = 4,

    /** Whether ktfmt should remove imports that are not used. */
    val removeUnusedImports: Boolean = true,

    /**
     * Print the Ops generated by KotlinInputAstVisitor to help reason about formatting (i.e.,
     * newline) decisions
     */
    val debuggingPrintOpsAfterFormatting: Boolean = false
) {
  enum class Style {
    FACEBOOK,
    DROPBOX,
    GOOGLE
  }
}

/**
 * format formats the Kotlin code given in 'code' and returns it as a string. This method is
 * accessed through Reflection.
 */
@Throws(FormatterException::class, ParseError::class)
fun format(code: String): String = format(FormattingOptions(), code)

/**
 * format formats the Kotlin code given in 'code' with 'removeUnusedImports' and returns it as a
 * string. This method is accessed through Reflection.
 */
@Throws(FormatterException::class, ParseError::class)
fun format(code: String, removeUnusedImports: Boolean): String =
    format(FormattingOptions(removeUnusedImports = removeUnusedImports), code)

/**
 * format formats the Kotlin code given in 'code' with the 'maxWidth' and returns it as a string.
 */
@Throws(FormatterException::class, ParseError::class)
fun format(options: FormattingOptions, code: String): String {
  checkEscapeSequences(code)

  val lfCode = StringUtilRt.convertLineSeparators(code)
  val sortedImports = sortedAndDistinctImports(lfCode)
  val pretty = prettyPrint(sortedImports, options, "\n")
  val noRedundantElements = dropRedundantElements(pretty, options)
  return prettyPrint(noRedundantElements, options, Newlines.guessLineSeparator(code)!!)
}

/** prettyPrint reflows 'code' using google-java-format's engine. */
private fun prettyPrint(code: String, options: FormattingOptions, lineSeparator: String): String {
  val file = Parser.parse(code)
  val kotlinInput = KotlinInput(code, file)
  val javaOutput = JavaOutput(lineSeparator, kotlinInput, KDocCommentsHelper(lineSeparator))
  val builder = OpsBuilder(kotlinInput, javaOutput)
  file.accept(createAstVisitor(options, builder))
  builder.sync(kotlinInput.text.length)
  builder.drain()
  val ops = builder.build()
  if (options.debuggingPrintOpsAfterFormatting) {
    printOps(ops)
  }
  val doc = DocBuilder().withOps(ops).build()
  doc.computeBreaks(javaOutput.commentsHelper, options.maxWidth, Doc.State(+0, 0))
  doc.write(javaOutput)
  javaOutput.flush()

  val tokenRangeSet =
      kotlinInput.characterRangesToTokenRanges(ImmutableList.of(Range.closedOpen(0, code.length)))
  return replaceTombstoneWithTrailingWhitespace(
      JavaOutput.applyReplacements(code, javaOutput.getFormatReplacements(tokenRangeSet)))
}

fun createAstVisitor(options: FormattingOptions, builder: OpsBuilder): PsiElementVisitor {
  val visitorClassName =
      when {
        KotlinVersion.CURRENT.major == 1 && KotlinVersion.CURRENT.minor == 4 ->
            "com.facebook.ktfmt.Kotlin14InputAstVisitor"
        else ->
            throw RuntimeException("Unsupported runtime Kotlin version: " + KotlinVersion.CURRENT)
      }

  return Class.forName(visitorClassName)
      .asSubclass(KotlinInputAstVisitorBase::class.java)
      .getConstructor(FormattingOptions::class.java, OpsBuilder::class.java)
      .newInstance(options, builder)
}

private fun checkEscapeSequences(code: String) {
  var index = code.indexOfWhitespaceTombstone()
  if (index == -1) {
    index = indexOfCommentEscapeSequences(code)
  }
  if (index != -1) {
    throw ParseError(
        "ktfmt does not support code which contains one of {\\u0003, \\u0004, \\u0005} character" +
            "; escape it",
        StringUtil.offsetToLineColumn(code, index))
  }
}

fun sortedAndDistinctImports(code: String): String {
  val file = Parser.parse(code)

  val importList = file.importList ?: return code
  if (importList.imports.isEmpty()) {
    return code
  }

  fun findNonImportElement(): PsiElement? {
    var element = importList.firstChild
    while (element != null) {
      if (element !is KtImportDirective && element !is PsiWhiteSpace) {
        return element
      }
      element = element.nextSibling
    }
    return null
  }

  val nonImportElement = findNonImportElement()
  if (nonImportElement != null) {
    throw ParseError(
        "Imports not contiguous (perhaps a comment separates them?): " + nonImportElement.text,
        StringUtil.offsetToLineColumn(code, nonImportElement.startOffset))
  }
  fun canonicalText(importDirective: KtImportDirective) =
      importDirective.importedFqName?.asString() +
          " " +
          importDirective.alias?.text?.replace("`", "") +
          " " +
          if (importDirective.isAllUnder) "*" else ""

  val sortedImports = importList.imports.sortedBy(::canonicalText).distinctBy(::canonicalText)

  return code.replaceRange(
      importList.startOffset,
      importList.endOffset,
      sortedImports.joinToString(separator = "\n") { imprt -> imprt.text } + "\n")
}

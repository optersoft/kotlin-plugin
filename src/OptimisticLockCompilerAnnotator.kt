/*
 * Copyright (C) 2017 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified by Optersoft, S.L. (2026): copied from SQLDelight 2.4.0's Gradle plugin
 * (app/cash/sqldelight/core/annotators/OptimisticLockCompilerAnnotator.kt) into this package.
 */

package com.optersoft.sqldelight.runner

import app.cash.sqldelight.core.lang.validation.OptimisticLockValidator
import com.alecstrong.sql.psi.core.SqlAnnotationHolder
import com.alecstrong.sql.psi.core.SqlCompilerAnnotator
import com.intellij.psi.PsiElement

class OptimisticLockCompilerAnnotator : SqlCompilerAnnotator {
  private val optimisticLockValidator = OptimisticLockValidator()

  override fun annotate(element: PsiElement, annotationHolder: SqlAnnotationHolder) {
    optimisticLockValidator.annotate(element, null, annotationHolder)
  }
}

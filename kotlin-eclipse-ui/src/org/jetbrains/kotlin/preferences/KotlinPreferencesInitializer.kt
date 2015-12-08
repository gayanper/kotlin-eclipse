/*******************************************************************************
 * Copyright 2000-2015 JetBrains s.r.o.
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
package org.jetbrains.kotlin.preferences

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer
import org.jetbrains.kotlin.ui.Activator
import org.jetbrains.kotlin.ui.KotlinUsageReporter

public class KotlinPreferencesInitializer : AbstractPreferenceInitializer() {
    override fun initializeDefaultPreferences() {
        val kotlinStore = Activator.getDefault().preferenceStore
        
        with(kotlinStore) {
            setDefault(KotlinUsageReporter.LAST_UPDATE_KEY, 0L)
            setDefault(KotlinUsageReporter.UPDATE_USAGE_AVAILABLE_KEY, true)
            setDefault(KotlinUsageReporter.ASK_FOR_USAGE_REPORTING_KEY, true)
        }
    }
}
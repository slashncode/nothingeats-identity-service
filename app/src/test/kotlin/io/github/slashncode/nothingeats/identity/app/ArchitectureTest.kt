package io.github.slashncode.nothingeats.identity.app

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import io.kotest.core.spec.style.FunSpec

/**
 * Module boundaries already stop the domain from seeing frameworks at compile time.
 * These rules cover what the module graph cannot express.
 */
class ArchitectureTest :
    FunSpec({
        val production = Konsist.scopeFromProduction()

        test("the domain only uses the Kotlin and Java standard libraries") {
            production.files
                .filter { it.packagee?.name?.contains(".domain") == true }
                .assertTrue { file -> file.imports.all { it.name.startsWith("kotlin.") || it.name.startsWith("java.") } }
        }

        test("HTTP adapters never touch persistence directly") {
            production.files
                .filter { it.packagee?.name?.contains(".adapters.http") == true }
                .assertFalse { file -> file.imports.any { it.name.contains(".adapters.persistence") } }
        }

        test("use cases do not depend on adapters") {
            production.files
                .filter { it.packagee?.name?.contains(".application") == true }
                .assertFalse { file -> file.imports.any { it.name.contains(".adapters.") } }
        }
    })

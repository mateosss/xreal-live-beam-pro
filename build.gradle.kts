plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Formatting: ./gradlew spotlessApply fixes, spotlessCheck (CI) verifies; rules below.
    id("com.diffplug.spotless") version "8.1.0"
}

/** ktlint rules: the code favours dense, long lines, so the wrapping rules are off; the rest is enforced. */
val ktlintRules = mapOf(
    "ktlint_code_style" to "intellij_idea",
    "max_line_length" to "off",
    "ktlint_standard_argument-list-wrapping" to "disabled",
    "ktlint_standard_parameter-list-wrapping" to "disabled",
    "ktlint_standard_wrapping" to "disabled",
    "ktlint_standard_statement-wrapping" to "disabled",
    "ktlint_standard_multiline-if-else" to "disabled",
    "ktlint_standard_multiline-loop" to "disabled",
    "ktlint_standard_function-literal" to "disabled",
    "ktlint_standard_trailing-comma-on-call-site" to "disabled",
    "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
    "ktlint_standard_function-signature" to "disabled",
    "ktlint_standard_class-signature" to "disabled",
    "ktlint_standard_chain-method-continuation" to "disabled",
    "ktlint_standard_multiline-expression-wrapping" to "disabled",
    "ktlint_standard_if-else-wrapping" to "disabled",
    "ktlint_standard_binary-expression-wrapping" to "disabled",
    "ktlint_standard_condition-wrapping" to "disabled",
    "ktlint_standard_string-template-indent" to "disabled",
    "ktlint_standard_blank-line-between-when-conditions" to "disabled",
)

spotless {
    kotlin {
        target("app/src/**/*.kt")
        ktlint("1.8.0").editorConfigOverride(ktlintRules)
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint("1.8.0").editorConfigOverride(ktlintRules)
    }
}

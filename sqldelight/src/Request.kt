package com.optersoft.sqldelight

import java.nio.file.Path

/**
 * One job for the SQLDelight compiler: everything the forked JVM needs to know, and nothing it has
 * to look up.
 *
 * It travels to that JVM on its command line, one `key=value` argument per field ([toArgs]), and is
 * read back there by [fromArgs]. A list is one argument per element, under the same key.
 */
internal data class Request(
    val mode: Mode,
    val sourceDir: Path,
    val outputDir: Path,
    val moduleName: String,
    val packageName: String,
    val databaseName: String,
    val dialect: String,
    val deriveSchemaFromMigrations: Boolean,
    val verifyMigrations: Boolean,
    val verifyDefinitions: Boolean,
    val generateAsync: Boolean,
    val treatNullAsUnknownForEquality: Boolean,
    val expandSelectStar: Boolean,
    val codegenExcludedColumns: List<String>,
) {
    /** What the forked JVM does with the request. */
    enum class Mode {
        /** Write the Kotlin for the `.sq` (and `.sqm`) files into [outputDir]. */
        Generate,

        /** The `migrations` check. */
        Verify,

        /** Write the current schema, as an empty `<version>.db`, into [outputDir]. */
        Schema,
    }

    fun toArgs(): List<String> = buildList {
        fun put(key: String, value: Any) = add("$key=$value")
        put(MODE, mode.name)
        put(SOURCE_DIR, sourceDir.toAbsolutePath())
        put(OUTPUT_DIR, outputDir.toAbsolutePath())
        put(MODULE_NAME, moduleName)
        put(PACKAGE_NAME, packageName)
        put(DATABASE_NAME, databaseName)
        put(DIALECT, dialect)
        put(DERIVE_SCHEMA, deriveSchemaFromMigrations)
        put(VERIFY_MIGRATIONS, verifyMigrations)
        put(VERIFY_DEFINITIONS, verifyDefinitions)
        put(GENERATE_ASYNC, generateAsync)
        put(NULL_AS_UNKNOWN, treatNullAsUnknownForEquality)
        put(EXPAND_SELECT_STAR, expandSelectStar)
        codegenExcludedColumns.forEach { put(EXCLUDED_COLUMN, it) }
    }

    companion object {
        /** The class whose `main` the forked JVM runs: `Runner.kt`. */
        const val MAIN_CLASS = "com.optersoft.sqldelight.runner.Runner"

        private const val MODE = "mode"
        private const val SOURCE_DIR = "sourceDir"
        private const val OUTPUT_DIR = "outputDir"
        private const val MODULE_NAME = "moduleName"
        private const val PACKAGE_NAME = "packageName"
        private const val DATABASE_NAME = "databaseName"
        private const val DIALECT = "dialect"
        private const val DERIVE_SCHEMA = "deriveSchemaFromMigrations"
        private const val VERIFY_MIGRATIONS = "verifyMigrations"
        private const val VERIFY_DEFINITIONS = "verifyDefinitions"
        private const val GENERATE_ASYNC = "generateAsync"
        private const val NULL_AS_UNKNOWN = "treatNullAsUnknownForEquality"
        private const val EXPAND_SELECT_STAR = "expandSelectStar"
        private const val EXCLUDED_COLUMN = "codegenExcludedColumn"

        fun fromArgs(args: Array<String>): Request {
            val single = HashMap<String, String>()
            val excluded = ArrayList<String>()
            for (arg in args) {
                val key = arg.substringBefore('=', missingDelimiterValue = "")
                require(key.isNotEmpty()) { "Not a key=value argument: $arg" }
                val value = arg.substring(key.length + 1)
                if (key == EXCLUDED_COLUMN) {
                    excluded += value
                } else {
                    require(single.put(key, value) == null) { "Argument $key given twice" }
                }
            }

            fun text(key: String): String =
                single.remove(key) ?: throw IllegalArgumentException("Argument $key is missing")

            fun flag(key: String): Boolean =
                text(key).toBooleanStrictOrNull()
                    ?: throw IllegalArgumentException("Argument $key is not true or false")

            val request =
                Request(
                    mode = Mode.valueOf(text(MODE)),
                    sourceDir = Path.of(text(SOURCE_DIR)),
                    outputDir = Path.of(text(OUTPUT_DIR)),
                    moduleName = text(MODULE_NAME),
                    packageName = text(PACKAGE_NAME),
                    databaseName = text(DATABASE_NAME),
                    dialect = text(DIALECT),
                    deriveSchemaFromMigrations = flag(DERIVE_SCHEMA),
                    verifyMigrations = flag(VERIFY_MIGRATIONS),
                    verifyDefinitions = flag(VERIFY_DEFINITIONS),
                    generateAsync = flag(GENERATE_ASYNC),
                    treatNullAsUnknownForEquality = flag(NULL_AS_UNKNOWN),
                    expandSelectStar = flag(EXPAND_SELECT_STAR),
                    codegenExcludedColumns = excluded,
                )
            require(single.isEmpty()) { "Unknown arguments: ${single.keys.sorted()}" }
            return request
        }
    }
}

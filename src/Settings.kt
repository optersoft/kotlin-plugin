package com.optersoft.sqldelight

import org.jetbrains.amper.plugins.Configurable
import org.jetbrains.amper.plugins.Dependency

/**
 * The settings of the plugin, written under `plugins: sqldelight:` in a module's `module.yaml`.
 *
 * They follow the options of SQLDelight's Gradle plugin, with the same names and defaults.
 */
@Configurable
interface Settings {
    /** The package of the generated `Database` class, e.g. `pets.db`. Required. */
    val packageName: String

    /** The name of the generated database class. */
    val databaseName: String
        get() = "Database"

    /** The folder with the `.sq` and `.sqm` files, relative to the module. */
    val sourceDir: String
        get() = "sqldelight"

    /**
     * The SQL the files are checked against, as the artifact ID of a SQLDelight dialect. Every
     * SQLite dialect comes with the plugin, from `sqlite-3-18-dialect` (the default, as in Gradle)
     * to `sqlite-3-44-dialect`. Any other, such as `postgresql-dialect`, also goes in
     * [compilerDependencies]: `app.cash.sqldelight:postgresql-dialect:2.4.0`.
     */
    val dialect: String
        get() = "sqlite-3-18-dialect"

    /**
     * More libraries for the SQLDelight compiler: another dialect, or a SQLDelight module such as
     * `app.cash.sqldelight:sqlite-json-module:2.4.0`.
     */
    val compilerDependencies: List<Dependency>
        get() = emptyList()

    /**
     * Build the database from the `.sqm` migrations instead of the `CREATE` statements in `.sq`
     * files.
     */
    val deriveSchemaFromMigrations: Boolean
        get() = false

    /**
     * Check that the `.sqm` migrations compile, and make the `migrations` check fail when there is
     * no `.db` schema file to verify them against.
     */
    val verifyMigrations: Boolean
        get() = false

    /**
     * In the `migrations` check, compare column definitions too (types, defaults), not only names.
     */
    val verifyDefinitions: Boolean
        get() = true

    /** Generate `suspend` query functions, for asynchronous drivers. */
    val generateAsync: Boolean
        get() = false

    /**
     * Treat `NULL` as unknown in `= ?` comparisons, as SQL does, instead of turning `= ?` into `IS
     * ?`.
     */
    val treatNullAsUnknownForEquality: Boolean
        get() = false

    /** Write `SELECT *` out as the list of columns in the generated SQL. */
    val expandSelectStar: Boolean
        get() = true

    /** Columns left out of the generated code, as `table.column`. */
    val codegenExcludedColumns: List<String>
        get() = emptyList()
}

package com.optersoft.sqldelight.runner

import app.cash.sqldelight.core.lang.SqlDelightQueriesFile
import app.cash.sqldelight.core.lang.util.forInitializationStatements
import app.cash.sqldelight.core.lang.util.rawSqlText
import app.cash.sqlite.migrations.CatalogDatabase
import app.cash.sqlite.migrations.ObjectDifferDatabaseComparator
import app.cash.sqlite.migrations.findDatabaseFiles
import com.optersoft.sqldelight.Request
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager

// The `migrations` check and the `schema` command follow SQLDelight's Gradle tasks
// VerifyMigrationTask and GenerateSchemaTask (Apache License 2.0, Copyright Square, Inc.).

/** The `migrations` check: what SQLDelight's Gradle task `verifySqlDelightMigration` does. */
@Suppress("ReturnCount")
internal fun verify(request: Request): Boolean {
    if (request.deriveSchemaFromMigrations) {
        say(
            "The schema is derived from the migrations (deriveSchemaFromMigrations), so there is nothing to compare."
        )
        return true
    }
    val environment = environment(request, scratch())
    if (!environment.dialect.isSqlite) {
        say("Migrations can only be verified for SQLite, not for ${request.dialect}.")
        return true
    }
    checkForGaps(environment)
    val current = currentDatabase(environment)
    val databaseFiles =
        listOf(request.sourceDir.toFile())
            .asSequence()
            .findDatabaseFiles()
            .sortedBy { it.name }
            .toList()
    if (databaseFiles.isEmpty()) {
        var migrations = 0
        environment.forMigrationFiles { migrations++ }
        if (migrations == 0 && !request.verifyMigrations) {
            say("No .sqm migrations and no .db schema file: nothing to verify.")
            return true
        }
        val message =
            "There is no .db file in ${relative(request.sourceDir.toString())} to verify the migrations against. " +
                "Make one with `kotlin do schema` before you change the schema."
        if (request.verifyMigrations) throw Problem(message)
        say(message)
        return true
    }
    val working = Files.createTempDirectory("sqldelight-verify").toFile()
    var ok = true
    for (dbFile in databaseFiles) {
        val (migrated, applied) = migratedDatabase(environment, dbFile, working)
        val comparator =
            ObjectDifferDatabaseComparator(ignoreDefinitions = !request.verifyDefinitions)
        val diff = buildString { comparator.compare(current, migrated).printTo(this) }
        val steps = if (applied.isEmpty()) "no migrations" else applied.joinToString(", ")
        if (diff.isEmpty()) {
            say("${dbFile.name} + $steps = the schema in the .sq files")
        } else {
            say("${dbFile.name} + $steps is not the schema in the .sq files:")
            say(diff.trimEnd().prependIndent("  "))
            ok = false
        }
    }
    working.deleteRecursively()
    return ok
}

/** The `schema` command: what SQLDelight's Gradle task `generateSqlDelightSchema` does. */
internal fun schema(request: Request): Boolean {
    val environment = environment(request, scratch())
    var version = 1L
    environment.forMigrationFiles { version = maxOf(version, it.version + 1) }
    val outputDir = request.outputDir.toFile()
    outputDir.mkdirs()
    val file = File(outputDir, "$version.db")
    file.delete()
    DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { connection ->
        initializationStatements(environment).forEach { connection.prepareStatement(it).execute() }
    }
    say("Wrote ${relative(file.path)}, the schema at version $version")
    return true
}

private fun currentDatabase(environment: SqlDelightEnvironment): CatalogDatabase =
    CatalogDatabase.withInitStatements(
        initializationStatements(environment).map {
            CatalogDatabase.InitStatement(it, "Error compiling $it")
        }
    )

private fun initializationStatements(environment: SqlDelightEnvironment): List<String> {
    val sourceFiles = ArrayList<SqlDelightQueriesFile>()
    environment.forSourceFiles { if (it is SqlDelightQueriesFile) sourceFiles.add(it) }
    val statements = ArrayList<String>()
    sourceFiles.forInitializationStatements(environment.dialect.allowsReferenceCycles) {
        statements.add(it)
    }
    return statements
}

private fun migratedDatabase(
    environment: SqlDelightEnvironment,
    dbFile: File,
    working: File,
): Pair<CatalogDatabase, List<String>> {
    val version =
        dbFile.nameWithoutExtension.toIntOrNull()
            ?: throw Problem(
                "${relative(dbFile.path)}: a schema file is named after its version, like 1.db"
            )
    val copy = dbFile.copyTo(File(working, dbFile.name), overwrite = true)
    val statements = ArrayList<CatalogDatabase.InitStatement>()
    val applied = ArrayList<String>()
    environment.forMigrationFiles { file ->
        if (file.name.none { it in '0'..'9' }) {
            throw Problem(
                "${file.name}: a migration file is named after the version it upgrades, like 1.sqm"
            )
        }
        if (version > file.version) return@forMigrationFiles
        applied += file.name
        file.sqlStmtList!!.stmtList.forEach {
            statements.add(
                CatalogDatabase.InitStatement(it.rawSqlText(), "Error running ${file.name}")
            )
        }
    }
    return CatalogDatabase.fromFile(copy.absolutePath, statements).also { copy.delete() } to applied
}

private fun checkForGaps(environment: SqlDelightEnvironment) {
    var last: Long? = null
    environment.forMigrationFiles {
        val expected = last?.plus(1) ?: it.version
        if (it.version != expected) {
            throw Problem(
                "There is a gap in the migrations: after $last.sqm comes ${it.name}, not $expected.sqm."
            )
        }
        last = it.version
    }
}

/** A temporary folder for SQLDelight's output that nobody reads. */
private fun scratch(): File =
    Files.createTempDirectory("sqldelight").toFile().apply { deleteOnExit() }

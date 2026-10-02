package com.optersoft.sqldelight

import java.io.File
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction

/**
 * Writes the Kotlin for the `.sq` files into [outputDir]: the module compiles it as generated
 * sources.
 */
@TaskAction
@OptIn(ExperimentalPathApi::class)
fun generate(
    @Input sourceDir: Path,
    @Output outputDir: Path,
    @Input compiler: Classpath,
    @Input extra: Classpath,
    moduleName: String,
    packageName: String,
    databaseName: String,
    dialect: String,
    deriveSchemaFromMigrations: Boolean,
    verifyMigrations: Boolean,
    generateAsync: Boolean,
    treatNullAsUnknownForEquality: Boolean,
    expandSelectStar: Boolean,
    codegenExcludedColumns: List<String>,
) {
    requireSourceDir(sourceDir, packageName)
    outputDir.deleteRecursively()
    outputDir.createDirectories()
    val request =
        Request(
            Request.Mode.Generate,
            sourceDir,
            outputDir,
            moduleName,
            packageName,
            databaseName,
            dialect,
            deriveSchemaFromMigrations,
            verifyMigrations,
            verifyDefinitions = true,
            generateAsync,
            treatNullAsUnknownForEquality,
            expandSelectStar,
            codegenExcludedColumns,
        )
    fork(request, compiler, extra, "SQLDelight could not generate the code (see above)")
}

/**
 * The `migrations` check: every `.db` schema file, migrated with the `.sqm` files, must be the
 * current schema.
 */
@TaskAction
fun verify(
    @Input sourceDir: Path,
    @Input compiler: Classpath,
    @Input extra: Classpath,
    moduleName: String,
    packageName: String,
    databaseName: String,
    dialect: String,
    deriveSchemaFromMigrations: Boolean,
    verifyMigrations: Boolean,
    verifyDefinitions: Boolean,
) {
    requireSourceDir(sourceDir, packageName)
    val request =
        Request(
            Request.Mode.Verify,
            sourceDir,
            sourceDir,
            moduleName,
            packageName,
            databaseName,
            dialect,
            deriveSchemaFromMigrations,
            verifyMigrations,
            verifyDefinitions,
            generateAsync = false,
            treatNullAsUnknownForEquality = false,
            expandSelectStar = true,
            codegenExcludedColumns = emptyList(),
        )
    fork(request, compiler, extra, "The migrations check failed (see above)")
}

/**
 * The `schema` command: writes `<version>.db`, an empty database with the current schema, into
 * [outputDir].
 */
@TaskAction(ExecutionAvoidance.Disabled)
fun schema(
    @Input sourceDir: Path,
    outputDir: String,
    @Input compiler: Classpath,
    @Input extra: Classpath,
    moduleName: String,
    packageName: String,
    databaseName: String,
    dialect: String,
    deriveSchemaFromMigrations: Boolean,
) {
    requireSourceDir(sourceDir, packageName)
    val request =
        Request(
            Request.Mode.Schema,
            sourceDir,
            Path.of(outputDir),
            moduleName,
            packageName,
            databaseName,
            dialect,
            deriveSchemaFromMigrations,
            verifyMigrations = false,
            verifyDefinitions = true,
            generateAsync = false,
            treatNullAsUnknownForEquality = false,
            expandSelectStar = true,
            codegenExcludedColumns = emptyList(),
        )
    fork(request, compiler, extra, "SQLDelight could not write the schema (see above)")
}

private fun requireSourceDir(sourceDir: Path, packageName: String) {
    if (!sourceDir.isDirectory()) {
        val folder = sourceDir.name
        val example = "$folder/${packageName.replace('.', '/')}/"
        throw SqlDelightFailure(
            "There is no folder `$folder` in the module. Put the .sq files in $example, " +
                "or name another folder in `sourceDir`."
        )
    }
}

/**
 * Runs SQLDelight's compiler in a JVM of its own. The compiler builds an IntelliJ environment with
 * global state, so it cannot share the toolchain's JVM, nor a JVM with another module's run.
 */
private fun fork(
    request: Request,
    compiler: Classpath,
    extra: Classpath,
    failure: String,
) {
    val plugin =
        Request::class.java.protectionDomain
            ?.codeSource
            ?.location
            ?: error("Cannot find the classes of the SQLDelight plugin")
    // The compiler's IntelliJ classes (compiler-env) must win over partial copies that other
    // libraries bring; the reader's extra libraries come next, so they can replace a bundled one.
    val (intellij, rest) =
        (compiler.resolvedFiles + extra.resolvedFiles).partition {
            it.name.startsWith("compiler-env-")
        }
    val (own, bundled) = rest.partition { it in extra.resolvedFiles }
    val classpath =
        (intellij + own + bundled + listOf(Path.of(plugin.toURI())))
            .distinct()
            .joinToString(File.pathSeparator) { it.absolutePathString() }

    val java = Path.of(System.getProperty("java.home"), "bin", "java").absolutePathString()
    val command =
        listOf(java) +
            jvmOptions() +
            listOf("-cp", classpath, Request.MAIN_CLASS) +
            request.toArgs()
    // What the JVM itself prints on standard error is kept aside and shown only after a crash.
    val jvmErrors = File.createTempFile("sqldelight", ".log")
    try {
        val process = ProcessBuilder(command).redirectError(jvmErrors).start()
        val output = process.inputStream.bufferedReader().readLines()
        when (process.waitFor()) {
            0 -> output.forEach { println(it) }
            1 -> {
                output.forEach { System.err.println(it) }
                throw SqlDelightFailure(failure)
            }
            else -> {
                (jvmErrors.readLines() + output).forEach { System.err.println(it) }
                throw SqlDelightFailure("The SQLDelight compiler crashed (see above)")
            }
        }
    } finally {
        jvmErrors.delete()
    }
}

/**
 * Keeps the JDK quiet about what the compiler's libraries do: the IntelliJ classes call
 * `sun.misc.Unsafe` (JEP 498 warns from JDK 24), and sqlite-jdbc loads a native library (JEP 472).
 * SQLDelight's Gradle plugin passes the same option for `Unsafe`.
 */
private fun jvmOptions(): List<String> {
    val feature = Runtime.version().feature()
    return buildList {
        add("-Dfile.encoding=UTF-8")
        add("-Djava.awt.headless=true")
        if (feature in 23..25) add("--sun-misc-unsafe-memory-access=allow")
        if (feature >= 22) add("--enable-native-access=ALL-UNNAMED")
    }
}

/**
 * A failure already explained by what was printed: the toolchain shows its message and no stack
 * trace is useful.
 */
class SqlDelightFailure(message: String) : RuntimeException(message, null, false, false) {
    override fun toString(): String = message.orEmpty()
}

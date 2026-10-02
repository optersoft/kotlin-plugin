@file:JvmName("Runner")

package com.optersoft.sqldelight.runner

import app.cash.sqldelight.core.SqlDelightCompilationUnit
import app.cash.sqldelight.core.SqlDelightDatabaseName
import app.cash.sqldelight.core.SqlDelightDatabaseOptions
import app.cash.sqldelight.core.SqlDelightException
import app.cash.sqldelight.core.SqlDelightSourceFolder
import app.cash.sqldelight.dialect.api.SqlDelightDialect
import com.optersoft.sqldelight.Request
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

// The program the `generate`, `verify` and `schema` tasks start in a JVM of their own (see `fork`
// in Tasks.kt). Its contract with that function:
//
// - standard output carries only what the reader should see, one line at a time, through `say`;
// - standard error is for everything else, and is shown only after a crash;
// - the exit code is 0 when the job succeeded, 1 when it failed for a reason already printed, and
//   anything else when the program itself broke.

private const val SUCCESS = 0
private const val FAILURE = 1
private const val CRASH = 2

/** Standard output as the JVM opened it, before [main] points `System.out` at standard error. */
private val output = PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8)

/**
 * A failure the reader can fix, worded for them: it is printed as it is, with no stack trace.
 */
internal class Problem(message: String) : Exception(message)

/** Prints one line for the reader. */
internal fun say(line: String) {
    output.println(line)
}

/**
 * Prints a message of SQLDelight's, or of ours, with its paths made relative and without its blank
 * lines (an error ends with one, and an error at the end of a line has an empty marker line).
 */
private fun tell(message: String) {
    relative(message).lines().filter { it.isNotBlank() }.forEach(::say)
}

fun main(args: Array<String>) {
    // The IntelliJ classes under the compiler print to System.out when they please. Only `say`
    // writes to the real standard output; anything else lands on standard error.
    System.setOut(System.err)
    val code =
        try {
            if (run(Request.fromArgs(args))) SUCCESS else FAILURE
        } catch (problem: Problem) {
            tell(problem.message.orEmpty())
            FAILURE
        } catch (failure: SqlDelightException) {
            tell(failure.message.orEmpty())
            FAILURE
        } catch (crash: Throwable) {
            crash.printStackTrace()
            CRASH
        }
    output.flush()
    // The IntelliJ environment leaves threads behind, so the JVM is told to stop rather than left
    // to notice.
    exitProcess(code)
}

private fun run(request: Request): Boolean {
    val sources = request.sourceDir.toFile()
    if (!sources.isDirectory) {
        val example = "${sources.name}/${request.packageName.replace('.', '/')}/"
        throw Problem(
            "There is no folder `${sources.name}` in the module. Put the .sq files in $example, " +
                "or name another folder in `sourceDir`."
        )
    }
    return when (request.mode) {
        Request.Mode.Generate -> generate(request)
        Request.Mode.Verify -> verify(request)
        Request.Mode.Schema -> schema(request)
    }
}

/**
 * Writes the Kotlin into the request's output folder, or prints SQLDelight's errors as
 * `path:line:column message`, each followed by the lines it is about.
 */
private fun generate(request: Request): Boolean {
    val outputDir = request.outputDir.toFile()
    outputDir.mkdirs()
    // The compiler logs how long each file took; nobody here asked.
    val status = environment(request, outputDir).generateSqlDelightFiles { }
    return when (status) {
        is SqlDelightEnvironment.CompilationStatus.Success -> true
        is SqlDelightEnvironment.CompilationStatus.Failure -> {
            status.errors.forEach(::tell)
            false
        }
    }
}

/**
 * SQLDelight's compiler set up for the request: its source folder, the package and name of the
 * database class, the dialect and the code generation options. Generated files go to [outputDir].
 */
internal fun environment(request: Request, outputDir: File): SqlDelightEnvironment {
    val source = request.sourceDir.toFile().absoluteFile
    val options = DatabaseOptions(request)
    val unit = CompilationUnit(request.moduleName, source, outputDir.absoluteFile)
    return SqlDelightEnvironment(
        properties = options,
        compilationUnit = unit,
        verifyMigrations = request.verifyMigrations,
        dialect = dialect(request.dialect),
        moduleName = request.moduleName,
    )
}

private class DatabaseOptions(request: Request) : SqlDelightDatabaseOptions {
    override val packageName = request.packageName
    override val className = request.databaseName
    override val dependencies = emptyList<SqlDelightDatabaseName>()
    override val deriveSchemaFromMigrations = request.deriveSchemaFromMigrations
    override val treatNullAsUnknownForEquality = request.treatNullAsUnknownForEquality
    override val generateAsync = request.generateAsync
    override val expandSelectStar = request.expandSelectStar
    override val codegenExcludedColumns = request.codegenExcludedColumns.toSet()
}

private class CompilationUnit(
    override val name: String,
    source: File,
    override val outputDirectoryFile: File,
) : SqlDelightCompilationUnit {
    override val sourceFolders = setOf<SqlDelightSourceFolder>(SourceFolder(source))
}

private class SourceFolder(override val folder: File) : SqlDelightSourceFolder {
    override val dependency = false
}

/**
 * The dialect named by its artifact ID, such as `sqlite-3-38-dialect`.
 *
 * Every dialect jar registers its class as a service of [SqlDelightDialect]; the jar's file name,
 * less its version, is the artifact ID. Several dialects are on the classpath at once (each SQLite
 * one depends on the one before), so the service loader alone cannot choose.
 */
private fun dialect(name: String): SqlDelightDialect {
    val wanted = name.trim().split(':').let { if (it.size >= 2) it[1] else it[0] }
    val known = dialectsOnClasspath()
    val className =
        known[wanted]
            ?: throw Problem(
                "There is no dialect `$name` for SQLDelight. The compiler has " +
                    known.keys.sorted().joinToString(", ") +
                    "; another one goes in `compilerDependencies`, as " +
                    "app.cash.sqldelight:postgresql-dialect:2.4.0."
            )
    val loader = SqlDelightDialect::class.java.classLoader
    return Class.forName(className, true, loader)
        .asSubclass(SqlDelightDialect::class.java)
        .getDeclaredConstructor()
        .newInstance()
}

/** Artifact ID → dialect class, for every dialect jar the compiler can see. */
private fun dialectsOnClasspath(): Map<String, String> {
    val service = "META-INF/services/${SqlDelightDialect::class.java.name}"
    val versioned = Regex("""^(.+?)-\d+\..*\.jar$""")
    val found = sortedMapOf<String, String>()
    for (url in SqlDelightDialect::class.java.classLoader.getResources(service)) {
        // jar:file:/…/sqlite-3-38-dialect-2.4.0.jar!/META-INF/services/…
        val jar = url.toString().substringBefore("!/").substringAfterLast('/')
        val artifact = versioned.find(jar)?.groupValues?.get(1) ?: continue
        val className =
            url.openStream().bufferedReader().useLines { lines ->
                lines.map { it.substringBefore('#').trim() }.firstOrNull { it.isNotEmpty() }
            } ?: continue
        found.putIfAbsent(artifact, className)
    }
    return found
}

/**
 * [text] with every absolute path under the working folder written relative to it, so that a
 * message names `sqldelight/pets/db/Dog.sq` rather than where the project happens to live.
 */
internal fun relative(text: String): String {
    val here = File(System.getProperty("user.dir")).absoluteFile
    val roots =
        listOf(here.path, here.canonicalPath)
            .flatMap { listOf(it, it.replace('\\', '/')) }
            .map { it.trimEnd('/', '\\') }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }
    // Windows does not care about the case of a path, and the IntelliJ classes may spell the drive
    // letter differently from the JVM.
    val windows = File.separatorChar == '\\'
    var result = text
    for (root in roots) {
        val prefix = Regex.escape(root) + """[/\\]"""
        val pattern =
            if (windows) Regex(prefix, RegexOption.IGNORE_CASE) else Regex(prefix)
        result = result.replace(pattern, "")
    }
    return result
}

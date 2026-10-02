# Kotlin Toolchain plugins

Plugins for the [Kotlin Toolchain](https://kotlin-toolchain.org/) (0.13), by [Optersoft](https://optersoft.com).

| Plugin | What it does |
|---|---|
| [`sqldelight`](sqldelight) | Generates Kotlin from [SQLDelight](https://sqldelight.github.io/sqldelight/) `.sq` and `.sqm` files, and verifies migrations |

The Kotlin Toolchain cannot download a plugin from a repository yet: a plugin is a module **of the
project that uses it**. So you copy the plugin's folder into your project, at a fixed version.

## Add a plugin to your project

Every release tags the whole repository (`v0.1.1`) and, for each plugin, a history that holds
**only that plugin's folder** (`sqldelight-v0.1.1`). Copy that one with `git subtree` (your project
must be a Git repository with at least one commit):

```shell
git subtree add --prefix=plugins/sqldelight https://github.com/optersoft/kotlin-plugin.git sqldelight-v0.1.1 --squash
```

You get `plugins/sqldelight/` — `module.yaml`, `plugin.yaml`, `src/`, the licence — as ordinary files
in one commit of yours. Then make it a module of the project and register it, in `project.yaml`:

```yaml
modules:
  - plugins/sqldelight

plugins:
  - ./plugins/sqldelight
```

A later version replaces the folder the same way:

```shell
git subtree pull --prefix=plugins/sqldelight https://github.com/optersoft/kotlin-plugin.git sqldelight-v0.2.0 --squash
```

Without Git, download the repository at a tag and copy its `sqldelight` folder to `plugins/sqldelight`.

## `sqldelight`

Enable it in the module that holds the SQL, with the package of the generated code:

```yaml
product: jvm/app

dependencies:
  - app.cash.sqldelight:sqlite-driver:2.4.0

plugins:
  sqldelight:
    enabled: true
    packageName: pets.db
    dialect: sqlite-3-38-dialect
```

Put the `.sq` files under `sqldelight/`, in folders that spell their package —
`sqldelight/pets/db/Dog.sq` — and build. The Kotlin is written to
`build/tasks/_<module>_generate@sqldelight/` and compiled with the module; the task runs only when a
file under `sqldelight/` changed.

It runs SQLDelight **2.4.0**'s own compiler, in a JVM of its own, with libraries the toolchain
downloads from Maven Central the first time. The generated code needs SQLDelight's runtime, which
every driver brings (`sqlite-driver` above), or `app.cash.sqldelight:runtime:2.4.0` in a library.

### Settings

| Setting | Default | |
|---|---|---|
| `packageName` | — (required) | Package of the generated `Database` class |
| `databaseName` | `Database` | Name of the generated database class |
| `sourceDir` | `sqldelight` | Folder of the `.sq`/`.sqm` files, in the module |
| `dialect` | `sqlite-3-18-dialect` | Every SQLite dialect up to `sqlite-3-44-dialect` comes with the plugin; another one (`postgresql-dialect`, …) also goes in `compilerDependencies` |
| `compilerDependencies` | `[]` | More libraries for the compiler: a dialect, `app.cash.sqldelight:sqlite-json-module:2.4.0` |
| `deriveSchemaFromMigrations` | `false` | The schema is the `.sqm` files, not `CREATE` statements in `.sq` files |
| `verifyMigrations` | `false` | Compile the `.sqm` files against the `.db` schema files, and fail the `migrations` check when there is none |
| `verifyDefinitions` | `true` | The `migrations` check compares column definitions too, not only names |
| `generateAsync` | `false` | `suspend` query functions, for asynchronous drivers |
| `treatNullAsUnknownForEquality` | `false` | Keep `= ?` as SQL's `=` for a null argument, instead of `IS` |
| `expandSelectStar` | `true` | Write `SELECT *` out as columns in the generated SQL |
| `codegenExcludedColumns` | `[]` | Columns (`table.column`) left out of the generated code |

They are the options of SQLDelight's Gradle plugin, with the same names and defaults.

### Migrations

Two tasks follow the Gradle plugin's `generateSqlDelightSchema` and `verifySqlDelightMigration`:

```shell
./kotlin do schema -m <module>     # writes sqldelight/databases/<version>.db: the current schema
./kotlin check migrations          # every .db + the .sqm files after it = the schema in the .sq files
```

Make `1.db` before the first change to a schema that is already in use, write `1.sqm` with the
change, and `kotlin check migrations` tells you whether the migration gives the database the `.sq`
files describe — and prints the difference when it does not. `kotlin check` runs it with the tests.

## Examples and tests

`examples/` holds one project per case, built and tested on every push:

```shell
./kotlin build
./kotlin check        # the tests and the migrations check
```

- `sqldelight-basic` — a table, queries, `RETURNING` (dialect 3.38);
- `sqldelight-migration` — `.sq` + `1.sqm` + `databases/1.db`, `verifyMigrations`;
- `sqldelight-derived` — `deriveSchemaFromMigrations`.

## Licence

[Apache License 2.0](LICENSE). Two files under `sqldelight/src` are copied from SQLDelight
(Copyright Square, Inc., Apache 2.0), and the migration tasks follow its Gradle plugin; see
[NOTICE](NOTICE).

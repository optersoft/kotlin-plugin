import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import pets.db.Database
import pets.db.Dog

class MigrationTest {
    @Test
    fun theSchemaHasOneMigration() {
        assertEquals(2L, Database.Schema.version)
    }

    @Test
    fun anOldDatabaseKeepsItsDogs() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(
            null,
            "CREATE TABLE Dog (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL)",
            0,
        )
        driver.execute(null, "INSERT INTO Dog (name) VALUES ('Laika')", 0)
        Database.Schema.migrate(driver, oldVersion = 1, newVersion = Database.Schema.version)
        val database = Database(driver)
        database.dogQueries.insert("Balto", 1919)
        assertEquals(
            listOf(Dog(2, "Balto", 1919), Dog(1, "Laika", null)),
            database.dogQueries.select().executeAsList(),
        )
        driver.close()
    }
}

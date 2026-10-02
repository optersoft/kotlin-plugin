import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import pets.db.Database
import pets.db.Dog

class DerivedTest {
    @Test
    fun theSchemaIsTheMigrations() {
        assertEquals(3L, Database.Schema.version)
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, schema = Database.Schema)
        val database = Database(driver)
        database.dogQueries.insert("Laika", 1954)
        assertEquals(listOf(Dog(1, "Laika", 1954)), database.dogQueries.select().executeAsList())
        driver.close()
    }
}

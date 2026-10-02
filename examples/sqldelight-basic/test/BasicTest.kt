import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import pets.db.Database

class BasicTest {
    @Test
    fun insertReturnsTheRow() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, schema = Database.Schema)
        val database = Database(driver)
        val laika = database.dogQueries.insert("Laika", 1954).executeAsOne()
        database.dogQueries.insert("Balto", 1919).executeAsOne()
        assertEquals(1L, laika.id)
        assertEquals(listOf("Balto"), database.dogQueries.selectNames(1950).executeAsList())
        driver.close()
    }
}

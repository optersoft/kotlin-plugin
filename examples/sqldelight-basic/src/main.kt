import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import pets.db.Database

fun main() {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, schema = Database.Schema)
    val database = Database(driver)
    database.dogQueries.insert("Laika", 1954).executeAsOne()
    database.dogQueries.insert("Balto", 1919).executeAsOne()
    for (dog in database.dogQueries.select().executeAsList()) {
        println(dog)
    }
    driver.close()
}

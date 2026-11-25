package org.geocrew.core.formats

import java.io.Closeable
import java.io.File

/**
 * The few SQLite calls file formats built on SQLite (GeoPackage) need. This
 * module is plain Kotlin, so the app supplies Android's SQLite and tests a
 * JDBC driver.
 */
fun interface SqlEngine {
    /** Opens [file], creating an empty database if it doesn't exist. */
    fun open(file: File): SqlDatabase
}

interface SqlDatabase : Closeable {
    /** Runs a statement that returns no rows. Arguments are null, Long, Double, String or ByteArray. */
    fun execute(sql: String, args: List<Any?> = emptyList())

    /** Runs a query; each row's values are null, Long, Double, String or ByteArray. */
    fun query(sql: String, args: List<Any?> = emptyList()): List<List<Any?>>

    /** Runs [block] in one transaction, rolled back if it throws. */
    fun <T> transaction(block: () -> T): T
}

package org.geocrew.core.data.repo

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.geocrew.core.formats.SqlDatabase
import org.geocrew.core.formats.SqlEngine
import java.io.File

/** Android's own SQLite, for file formats built on it (GeoPackage). */
object AndroidSqlEngine : SqlEngine {
    override fun open(file: File): SqlDatabase = Db(SQLiteDatabase.openOrCreateDatabase(file, null))

    private class Db(private val db: SQLiteDatabase) : SqlDatabase {
        override fun execute(sql: String, args: List<Any?>) {
            val pragma = USER_VERSION.matchEntire(sql.trim())
            when {
                // execSQL refuses PRAGMA with arguments on some versions; the setter is portable.
                pragma != null -> db.version = pragma.groupValues[1].toInt()
                args.isEmpty() -> db.execSQL(sql)
                else -> db.execSQL(sql, args.map(::bindable).toTypedArray())
            }
        }

        override fun query(sql: String, args: List<Any?>): List<List<Any?>> {
            // rawQuery only binds strings, so arguments are bound by type through a cursor factory.
            val c = db.rawQueryWithFactory({ _, driver, table, q ->
                args.forEachIndexed { i, a ->
                    when (a) {
                        null -> q.bindNull(i + 1)
                        is Long -> q.bindLong(i + 1, a)
                        is Int -> q.bindLong(i + 1, a.toLong())
                        is Double -> q.bindDouble(i + 1, a)
                        is Float -> q.bindDouble(i + 1, a.toDouble())
                        is ByteArray -> q.bindBlob(i + 1, a)
                        is Boolean -> q.bindLong(i + 1, if (a) 1 else 0)
                        else -> q.bindString(i + 1, a.toString())
                    }
                }
                android.database.sqlite.SQLiteCursor(driver, table, q)
            }, sql, null, null)
            return c.use { rows(it) }
        }

        private fun rows(c: Cursor): List<List<Any?>> {
            val out = ArrayList<List<Any?>>(c.count.coerceAtLeast(0))
            while (c.moveToNext()) {
                out += List(c.columnCount) { i ->
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                }
            }
            return out
        }

        override fun <T> transaction(block: () -> T): T {
            db.beginTransaction()
            try {
                val r = block()
                db.setTransactionSuccessful()
                return r
            } finally {
                db.endTransaction()
            }
        }

        override fun close() = db.close()

        private fun bindable(a: Any?): Any? = when (a) {
            is Boolean -> if (a) 1L else 0L
            is Int -> a.toLong()
            is Float -> a.toDouble()
            else -> a
        }
    }

    private val USER_VERSION = Regex("(?i)PRAGMA\\s+user_version\\s*=\\s*(\\d+);?")
}

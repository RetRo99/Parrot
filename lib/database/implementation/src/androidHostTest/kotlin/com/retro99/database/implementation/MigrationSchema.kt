package com.retro99.database.implementation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/** Shared with the historical one-book migration test; includes index definitions too. */
internal fun schemaOf(driver: SqlDriver): Map<String, List<String>> {
    val tables = driver.executeQuery(
        identifier = null,
        sql = "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND name NOT LIKE 'sqlite_%' ORDER BY name",
        mapper = { cursor ->
            val names = mutableListOf<String>()
            while (cursor.next().value) names += cursor.getString(0).orEmpty()
            QueryResult.Value(names)
        },
        parameters = 0,
    ).value
    return tables.associateWith { table ->
        val columns = driver.executeQuery(
            identifier = null,
            sql = "SELECT name, type, \"notnull\", pk, dflt_value FROM pragma_table_info('$table')",
            mapper = { cursor ->
                val definitions = mutableListOf<String>()
                while (cursor.next().value) {
                    definitions += listOf(
                        "column",
                        cursor.getString(0),
                        cursor.getString(1),
                        cursor.getLong(2),
                        cursor.getLong(3),
                        cursor.getString(4),
                    ).joinToString(":")
                }
                QueryResult.Value(definitions.sorted())
            },
            parameters = 0,
        ).value
        val indexes = driver.executeQuery(
            identifier = null,
            sql = "SELECT name, \"unique\", origin, partial FROM pragma_index_list('$table')",
            mapper = { cursor ->
                val definitions = mutableListOf<String>()
                while (cursor.next().value) {
                    definitions += listOf(
                        "index",
                        cursor.getString(0),
                        cursor.getLong(1),
                        cursor.getString(2),
                        cursor.getLong(3),
                    ).joinToString(":")
                }
                QueryResult.Value(definitions.sorted())
            },
            parameters = 0,
        ).value
        val indexColumns = driver.executeQuery(
            identifier = null,
            sql = "SELECT il.name, ix.seqno, ix.name, ix.desc, ix.coll, ix.key " +
                "FROM pragma_index_list('$table') il, pragma_index_xinfo(il.name) ix " +
                "ORDER BY il.name, ix.seqno",
            mapper = { cursor ->
                val definitions = mutableListOf<String>()
                while (cursor.next().value) {
                    definitions += listOf(
                        "index-column",
                        cursor.getString(0),
                        cursor.getLong(1),
                        cursor.getString(2),
                        cursor.getLong(3),
                        cursor.getString(4),
                        cursor.getLong(5),
                    ).joinToString(":")
                }
                QueryResult.Value(definitions)
            },
            parameters = 0,
        ).value
        columns + indexes + indexColumns
    }
}

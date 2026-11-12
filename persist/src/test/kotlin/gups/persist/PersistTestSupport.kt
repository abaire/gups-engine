package gups.persist

import java.nio.file.Path
import java.sql.DriverManager

/** The first column of the first row [sql] returns, read straight from the database [file]. */
internal fun count(file: Path, sql: String): Int =
  DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
    c.createStatement().use { st ->
      st.executeQuery(sql).use {
        it.next()
        it.getInt(1)
      }
    }
  }

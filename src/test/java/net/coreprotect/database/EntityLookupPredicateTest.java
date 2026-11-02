package net.coreprotect.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import net.coreprotect.config.ConfigHandler;
import org.junit.jupiter.api.Test;

class EntityLookupPredicateTest {
    @Test void placedAndOtherFiltersReturnTheSameRowsOnDuckDbAndSqlite() throws Exception {
        DatabaseType previous = ConfigHandler.databaseType;
        try {
            for (DatabaseType type : new DatabaseType[]{DatabaseType.DUCKDB, DatabaseType.SQLITE}) {
                ConfigHandler.databaseType = type;
                try (Connection connection = DriverManager.getConnection(type.isDuckDB() ? "jdbc:duckdb:" : "jdbc:sqlite::memory:");
                        Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE events (id INTEGER, action INTEGER, type INTEGER)");
                    statement.execute("INSERT INTO events VALUES (1,3,10),(2,3,20),(3,3,30),(4,4,10)");
                    assertEquals("1,2", rows(statement, predicate(true, false)));
                    assertEquals("3", rows(statement, predicate(false, true)));
                    assertEquals("1,2,3", rows(statement, predicate(true, true)));
                }
            }
        } finally {
            ConfigHandler.databaseType = previous;
        }
    }

    private String predicate(boolean placed, boolean other) throws Exception {
        Method method = LookupRaw.class.getDeclaredMethod("addEntityActionPredicate", List.class, int.class,
                boolean.class, boolean.class, String.class);
        method.setAccessible(true);
        List<String> predicates = new ArrayList<>();
        method.invoke(null, predicates, 3, placed, other, "10,20");
        return String.join(" OR ", predicates);
    }

    private String rows(Statement statement, String predicate) throws Exception {
        List<String> ids = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery("SELECT id FROM events WHERE " + predicate + " ORDER BY id")) {
            while (rows.next()) ids.add(rows.getString(1));
        }
        return String.join(",", ids);
    }
}

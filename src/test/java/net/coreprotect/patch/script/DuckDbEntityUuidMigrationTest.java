package net.coreprotect.patch.script;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import net.coreprotect.config.ConfigHandler;
import org.junit.jupiter.api.Test;

class DuckDbEntityUuidMigrationTest {
    private static final String COLUMNS = "rowid INTEGER NOT NULL DEFAULT nextval('co_entity_spawn_rowid_seq'), "
            + "time INTEGER, block_rowid BIGINT, kill_rowid INTEGER UNIQUE, uuid VARCHAR UNIQUE, wid INTEGER, "
            + "current_wid INTEGER, origin_x DOUBLE, origin_y DOUBLE, origin_z DOUBLE, x DOUBLE, y DOUBLE, "
            + "z DOUBLE, yaw FLOAT, pitch FLOAT, data BLOB, removed TINYINT";

    @Test void migrationRetainsRowsAndSequenceThenAcceptsRepeatedUuidAndKillIds() throws Exception {
        String previous = ConfigHandler.prefix;
        ConfigHandler.prefix = "co_";
        try (Connection db = DriverManager.getConnection("jdbc:duckdb:"); Statement sql = db.createStatement()) {
            sql.execute("CREATE SEQUENCE co_entity_spawn_rowid_seq START 22");
            sql.execute("CREATE TABLE co_entity_spawn (" + COLUMNS + ")");
            sql.execute("INSERT INTO co_entity_spawn VALUES (5,123,9,10,'same-uuid',1,2,1.5,2.5,3.5,4.5,5.5,6.5,7,8,'payload',1)");
            assertTrue(__2_25_2.patchDuckDB(sql));
            assertTrue(db.getAutoCommit());
            try (ResultSet row = sql.executeQuery("SELECT rowid,time,uuid,origin_x,x,data,removed FROM co_entity_spawn")) {
                assertTrue(row.next());
                assertEquals(5, row.getInt(1)); assertEquals(123, row.getInt(2));
                assertEquals("same-uuid", row.getString(3)); assertEquals(1.5, row.getDouble(4));
                assertEquals(4.5, row.getDouble(5)); assertArrayEquals("payload".getBytes(java.nio.charset.StandardCharsets.UTF_8), row.getBytes(6));
                assertEquals(1, row.getInt(7)); assertFalse(row.next());
            }
            sql.execute("INSERT INTO co_entity_spawn (uuid,kill_rowid) VALUES ('same-uuid',10)");
            try (ResultSet row = sql.executeQuery("SELECT MAX(rowid),COUNT(*) FROM co_entity_spawn")) {
                assertTrue(row.next()); assertEquals(22, row.getInt(1)); assertEquals(2, row.getInt(2));
            }
            assertTrue(__2_25_2.patchDuckDB(sql), "a second patch is idempotent");
            sql.execute("INSERT INTO co_entity_spawn (uuid,kill_rowid) VALUES ('same-uuid',10)");
            try (ResultSet row = sql.executeQuery("SELECT MAX(rowid),COUNT(*) FROM co_entity_spawn")) {
                assertTrue(row.next()); assertEquals(23, row.getInt(1)); assertEquals(3, row.getInt(2));
            }
        } finally { ConfigHandler.prefix = previous; }
    }

    @Test void failedRebuildRollsBackWithoutLosingOriginalRowsOrConstraints() throws Exception {
        String previous = ConfigHandler.prefix;
        ConfigHandler.prefix = "co_";
        try (Connection db = DriverManager.getConnection("jdbc:duckdb:"); Statement sql = db.createStatement()) {
            sql.execute("CREATE SEQUENCE co_entity_spawn_rowid_seq START 1");
            // A damaged source column forces failure after the replacement table has been created.
            sql.execute("CREATE TABLE co_entity_spawn (" + COLUMNS.replace(", data BLOB", "") + ")");
            sql.execute("INSERT INTO co_entity_spawn (rowid,uuid,kill_rowid) VALUES (5,'protected',10)");
            assertFalse(__2_25_2.patchDuckDB(sql));
            assertTrue(db.getAutoCommit());
            try (Statement read = db.createStatement(); ResultSet row = read.executeQuery("SELECT rowid,uuid FROM co_entity_spawn")) {
                assertTrue(row.next()); assertEquals(5,row.getInt(1)); assertEquals("protected",row.getString(2)); assertFalse(row.next());
            }
            try (Statement duplicate = db.createStatement()) {
                assertThrows(java.sql.SQLException.class, () -> duplicate.execute("INSERT INTO co_entity_spawn (uuid) VALUES ('protected')"));
            }
            try (Statement read = db.createStatement(); ResultSet row = read.executeQuery("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='co_entity_spawn_new'")) {
                assertTrue(row.next()); assertEquals(0,row.getInt(1));
            }
        } finally { ConfigHandler.prefix = previous; }
    }
}

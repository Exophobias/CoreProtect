package net.coreprotect.patch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.lang.reflect.*;
import java.sql.*;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.database.Database;
import net.coreprotect.database.DatabaseType;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.VersionUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseWatermarkTest {
    interface Work { void run(Connection db, Statement sql) throws Exception; }
    private void fixture(Work work) throws Exception {
        DatabaseType oldType = ConfigHandler.databaseType;
        String oldPrefix = ConfigHandler.prefix;
        boolean running = ConfigHandler.serverRunning, converting = ConfigHandler.converterRunning;
        ConfigHandler.databaseType = DatabaseType.DUCKDB; ConfigHandler.prefix = "co_";
        ConfigHandler.serverRunning = true; ConfigHandler.converterRunning = false;
        try (Connection db = DriverManager.getConnection("jdbc:duckdb:"); Statement sql = db.createStatement();
             var database = mockStatic(Database.class); var consumer = mockStatic(Consumer.class); var chat = mockStatic(Chat.class)) {
            Connection borrowed = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("close")) return null;
                try { return method.invoke(db, args); } catch (InvocationTargetException e) { throw e.getCause(); }
            });
            database.when(() -> Database.getConnection(true, 0)).thenReturn(borrowed);
            database.when(() -> Database.awaitConnectionDrain(anyLong())).thenReturn(true);
            database.when(() -> Database.recordDatabaseVersion(any(Statement.class), anyString())).thenCallRealMethod();
            database.when(() -> Database.createDatabaseTables("co_", true, db, DatabaseType.DUCKDB, false)).thenCallRealMethod();
            work.run(db, sql);
        } finally {
            ConfigHandler.databaseType = oldType; ConfigHandler.prefix = oldPrefix;
            ConfigHandler.serverRunning = running; ConfigHandler.converterRunning = converting;
        }
    }

    @ParameterizedTest @ValueSource(strings = {"2.24.1", "2.23.1"})
    void existingDuckDbRunsRequiredPatchesEvenWhenArtifactRemains25(String oldVersion) throws Exception {
        fixture((db, sql) -> {
            sql.execute("CREATE TABLE co_version(time INTEGER,version VARCHAR)");
            sql.execute("INSERT INTO co_version VALUES (1,'" + oldVersion + "')");
            sql.execute("CREATE TABLE co_sign(line_1 VARCHAR)");
            sql.execute("INSERT INTO co_sign VALUES ('protected text')");
            sql.execute("CREATE SEQUENCE co_entity_spawn_rowid_seq START 12");
            sql.execute("CREATE TABLE co_entity_spawn (rowid INTEGER DEFAULT nextval('co_entity_spawn_rowid_seq'), time INTEGER, block_rowid BIGINT, kill_rowid INTEGER UNIQUE, uuid VARCHAR UNIQUE, wid INTEGER, current_wid INTEGER, origin_x DOUBLE, origin_y DOUBLE, origin_z DOUBLE, x DOUBLE, y DOUBLE, z DOUBLE, yaw FLOAT, pitch FLOAT, data BLOB, removed TINYINT)");
            sql.execute("INSERT INTO co_entity_spawn (rowid,uuid,kill_rowid,data) VALUES (5,'retained',3,'payload')");
            Database.createDatabaseTables("co_", true, db, DatabaseType.DUCKDB, false);
            assertTrue(Patch.versionCheck(false));
            assertArrayEquals(new Integer[]{2,26,0}, Patch.getDatabaseVersion(db, true));
            try (ResultSet row = sql.executeQuery("SELECT line_1,rolled_back FROM co_sign")) {
                assertTrue(row.next()); assertEquals("protected text", row.getString(1)); assertEquals(0, row.getInt(2));
            }
            sql.execute("INSERT INTO co_entity_spawn(uuid,kill_rowid) VALUES ('retained',3)");
            try (ResultSet row = sql.executeQuery("SELECT COUNT(*),MAX(rowid) FROM co_entity_spawn")) {
                assertTrue(row.next()); assertEquals(2,row.getInt(1)); assertEquals(12,row.getInt(2));
            }
            assertTrue(Patch.versionCheck(false));
            try (ResultSet row = sql.executeQuery("SELECT COUNT(*) FROM co_version")) { assertTrue(row.next()); assertEquals(2,row.getInt(1)); }
        });
    }

    @Test void freshDatabaseRecordsReviewedWatermarkOnce() throws Exception {
        fixture((db, sql) -> {
            Database.createDatabaseTables("co_", true, db, DatabaseType.DUCKDB, false);
            assertTrue(Patch.versionCheck(false));
            assertArrayEquals(VersionUtils.getInternalDatabaseVersion(), Patch.getDatabaseVersion(db, true));
            assertTrue(Patch.versionCheck(false));
            try (ResultSet row = sql.executeQuery("SELECT COUNT(*) FROM co_version")) { assertTrue(row.next()); assertEquals(1,row.getInt(1)); }
        });
    }

    @Test void newerDatabaseRevisionIsRejectedWithoutDowngrade() throws Exception {
        fixture((db, sql) -> {
            sql.execute("CREATE TABLE co_version(time INTEGER,version VARCHAR)");
            sql.execute("INSERT INTO co_version VALUES (1,'2.26.1')");
            assertFalse(Patch.versionCheck(false));
            assertArrayEquals(new Integer[]{2,26,1}, Patch.getDatabaseVersion(db, true));
        });
    }
}

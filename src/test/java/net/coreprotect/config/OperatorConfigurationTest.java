package net.coreprotect.config;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.coreprotect.language.Language;
import net.coreprotect.language.Phrase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OperatorConfigurationTest {
    @TempDir Path directory;
    @BeforeEach void defaults() { Language.loadPhrases(); }
    Path main() { return directory.resolve("config.yml"); }
    void write(String name, String text) throws IOException { Files.writeString(directory.resolve(name), text); }
    OperatorConfigStore.Prepared prepare() throws IOException { return OperatorConfigStore.prepare(main(), Config.template(), Config.headers(), false, false); }

    @Test void freshCanonicalDefaultsAndCurrentBytesAreStableWithoutBackups() throws Exception {
        var first = prepare(); first.install(); first.verifyInstalled();
        String installed = Files.readString(main());
        assertTrue(installed.startsWith("# CoreProtect Config\nconfig-version: 1\n"));
        assertTrue(installed.indexOf("database-type:") < installed.indexOf("mysql-host:"));
        assertTrue(installed.indexOf("duckdb-threads:") < installed.indexOf("duckdb-consumer-delay:"));
        assertTrue(installed.indexOf("duckdb-consumer-delay:") < installed.indexOf("language:"));
        assertEquals("duckdb", first.parsed().values().get("database-type"));
        byte[] bytes = Files.readAllBytes(main());
        var current = prepare(); current.install(); current.verifyInstalled();
        assertArrayEquals(bytes, Files.readAllBytes(main()));
        try (var files = Files.list(directory)) { assertEquals(List.of("config.yml"), files.map(p -> p.getFileName().toString()).toList()); }
    }

    @Test void legacyPreservesCredentialsUnknownsAndOriginalDatabaseMeaning() throws Exception {
        write("config.yml", "mysql-password: \"p\\\\ath # colon: secret\"\nblock-break: false\ncustom-extension: 'operator choice'\ndonation-key:\n");
        var prepared = prepare();
        assertEquals("p\\ath # colon: secret", prepared.parsed().values().get("mysql-password"));
        assertEquals("sqlite", prepared.parsed().values().get("database-type"));
        assertEquals("false", prepared.parsed().values().get("block-break"));
        assertEquals("", prepared.parsed().values().get("donation-key"));
        assertEquals("operator choice", prepared.parsed().values().get("custom-extension"));
        prepared.install();
        String installed = Files.readString(main());
        assertTrue(installed.indexOf("custom-extension:") > installed.indexOf("worldedit:"));
        write("config.yml", "use-mysql: true\n");
        assertEquals("mysql", prepare().parsed().values().get("database-type"));
    }

    @Test void sparseWorldInheritsAndLanguagePreservesOperatorPhrases() throws Exception {
        write("config.yml", "block-break: false\n");
        write("world.yml", "block-place: false\ncustom-world: yes\n");
        write("language.yml", "RELOAD_SUCCESS: \"Saved # all: choices\"\ncustom_phrase: 'extra'\n");
        PreparedConfiguration.prepare(directory).activate(false);
        assertFalse(Config.getConfig("world").BLOCK_BREAK); assertFalse(Config.getConfig("world").BLOCK_PLACE);
        assertEquals("Saved # all: choices", Phrase.RELOAD_SUCCESS.getUserPhrase());
        String world = Files.readString(directory.resolve("world.yml"));
        assertFalse(world.contains("block-break:")); assertTrue(world.contains("config-version: 1"));
        assertTrue(Files.readString(directory.resolve("language.yml")).contains("CUSTOM_PHRASE: \"extra\""));
        byte[] bytes = Files.readAllBytes(main());
        PreparedConfiguration.prepare(directory).activate(true);
        assertArrayEquals(bytes, Files.readAllBytes(main()));
    }

    @ParameterizedTest @ValueSource(strings = {
        "config-version:", "config-version: null", "config-version: ~", "config-version: -1", "config-version: 1.0",
        "config-version: '1'", "config-version: 01", "config-version: 2", "config-version: 9999999999999999999999999999", "'config-version': 1",
        "config-version: 1\nconfig-version: 1", "config-version: 1\n---\nblock-break: true",
        "extension: null", "extension:", "extension: [one, two]", "extension: {one: two}", "extension:\n  nested: value",
        "<<: *defaults", "extension: !custom value", "block-break: true\nblock-break: false", "block-break: maybe",
        "mysql-port: invalid", "mysql-port: 70000", "database-type: unknown", "table-prefix: 'bad prefix'", "sqlite-database: '../other.db'",
        "mysql-password: \"unfinished", "mysql-password: \"one\" trailing"
    }) void malformedDocumentsAreRejectedByteForByte(String content) throws Exception {
        write("config.yml", content + "\n"); byte[] expected = Files.readAllBytes(main());
        assertThrows(IOException.class, this::prepare); assertArrayEquals(expected, Files.readAllBytes(main()));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void invalidUtf8AndNonRegularFilesAreNotMissing() throws Exception {
        Files.write(main(), new byte[] {(byte) 0xc3, (byte) 0x28}); assertThrows(IOException.class, this::prepare);
        Files.delete(main()); Files.createDirectory(main()); assertThrows(IOException.class, this::prepare);
    }

    @Test void sourceRaceAndInvalidLanguageRetainWholeGeneration() throws Exception {
        write("config.yml", "block-break: false\n"); write("language.yml", "RELOAD_SUCCESS: 'old message'\n");
        PreparedConfiguration.prepare(directory).activate(false);
        Config old = Config.getGlobal(); var oldLanguage = Config.languageState();
        var next = PreparedConfiguration.prepare(directory);
        write("config.yml", "block-break: true\n"); byte[] operator = Files.readAllBytes(main());
        assertThrows(IOException.class, () -> next.activate(true));
        assertSame(old, Config.getGlobal()); assertSame(oldLanguage, Config.languageState());
        assertEquals("old message", Phrase.RELOAD_SUCCESS.getUserPhrase()); assertArrayEquals(operator, Files.readAllBytes(main()));
        write("language.yml", "config-version: '1'\n");
        assertThrows(IOException.class, () -> PreparedConfiguration.prepare(directory));
        assertSame(old, Config.getGlobal()); assertSame(oldLanguage, Config.languageState()); assertArrayEquals(operator, Files.readAllBytes(main()));
    }

    @Test void worldMembershipAndCacheRacesRefusePublication() throws Exception {
        PreparedConfiguration.prepare(directory).activate(false); Config old = Config.getGlobal();
        var changedWorld = PreparedConfiguration.prepare(directory); write("newworld.yml", "block-break: false\n");
        assertThrows(IOException.class, () -> changedWorld.activate(true)); assertSame(old, Config.getGlobal());
        var changedCache = PreparedConfiguration.prepare(directory); write(".language", "RELOAD_SUCCESS: 'translated'\n");
        assertThrows(IOException.class, () -> changedCache.activate(true)); assertSame(old, Config.getGlobal());
    }

    @Test void databaseChangesRequireRestartBeforeAnyWrite() throws Exception {
        PreparedConfiguration.prepare(directory).activate(false); Config old = Config.getGlobal();
        write("config.yml", "database-type: mysql\nmysql-password: 'private'\n"); byte[] source = Files.readAllBytes(main());
        var next = PreparedConfiguration.prepare(directory);
        IOException failure = assertThrows(IOException.class, () -> next.activate(true));
        assertTrue(failure.getMessage().contains("restart required")); assertFalse(failure.getMessage().contains("private"));
        assertArrayEquals(source, Files.readAllBytes(main())); assertSame(old, Config.getGlobal());
    }

    @Test void currentFileIsNotFilledByAppendingAndFailedReadRetainsRuntime() throws Exception {
        write("config.yml", "config-version: 1\nblock-break: false\n"); PreparedConfiguration.prepare(directory).activate(false);
        byte[] exact = Files.readAllBytes(main()); Config old = Config.getGlobal(); var next = PreparedConfiguration.prepare(directory);
        Files.delete(directory.resolve("language.yml")); Files.createDirectory(directory.resolve("language.yml"));
        assertThrows(IOException.class, () -> next.activate(true)); assertSame(old, Config.getGlobal());
        assertArrayEquals(exact, Files.readAllBytes(main())); assertFalse(Files.readString(main()).contains("mysql-password"));
    }
    @Test void blacklistReloadAndRacesParticipateInTheSameGeneration() throws Exception {
        write("blacklist.txt", "blockedplayer\n");
        PreparedConfiguration.prepare(directory).activate(false);
        assertTrue(ConfigHandler.isBlacklisted("blockedplayer"));
        write("blacklist.txt", "newplayer\n");
        var next = PreparedConfiguration.prepare(directory);
        write("blacklist.txt", "changedagain\n");
        assertThrows(IOException.class, () -> next.activate(true));
        assertTrue(ConfigHandler.isBlacklisted("blockedplayer"));
        assertFalse(ConfigHandler.isBlacklisted("newplayer"));
        PreparedConfiguration.prepare(directory).activate(true);
        assertTrue(ConfigHandler.isBlacklisted("changedagain"));
        assertFalse(ConfigHandler.isBlacklisted("blockedplayer"));
    }
}

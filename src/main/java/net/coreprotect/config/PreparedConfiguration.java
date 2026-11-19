package net.coreprotect.config;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.coreprotect.language.Language;
import net.coreprotect.language.Phrase;

/** Prepare every operator file before any write; a partially installed migration is restart-idempotent. */
final class PreparedConfiguration {
    private final Path directory;
    private final Set<String> worldNames;
    private final List<OperatorConfigStore.Prepared> files;
    private final Config global;
    private final Map<String, Config> worlds;
    private final Map<String, String> language;
    private final byte[] translationSource;
    private final Map<String, String> translations;
    private final byte[] blacklistSource;
    private final ConfigHandler.BlacklistState blacklist;

    private PreparedConfiguration(Path directory, Set<String> names, List<OperatorConfigStore.Prepared> files,
            Config global, Map<String, Config> worlds, Map<String, String> language, byte[] cache, Map<String, String> translations, byte[] blacklistSource, ConfigHandler.BlacklistState blacklist) {
        this.directory = directory; this.worldNames = names; this.files = files; this.global = global;
        this.worlds = worlds; this.language = language; this.translationSource = cache; this.translations = translations;
        this.blacklistSource = blacklistSource; this.blacklist = blacklist;
    }

    static PreparedConfiguration prepare(Path directory) throws IOException {
        List<OperatorConfigStore.Prepared> files = new ArrayList<>();
        var main = OperatorConfigStore.prepare(directory.resolve(ConfigFile.CONFIG), Config.template(), Config.headers(), false, false);
        files.add(main);
        Config global = Config.fromValues(main.parsed().values(), null);
        var database = net.coreprotect.database.DatabaseType.parse(global.DATABASE_TYPE, global.MYSQL);
        global.MYSQL = database.isMySQL();
        if (database.isEmbedded()) global.PREFIX = "co_";
        Set<String> names = worldNames(directory);
        Map<String, Config> worlds = new LinkedHashMap<>();
        for (String name : names) {
            var world = OperatorConfigStore.prepare(directory.resolve(name), Config.template(), Config.headers(), false, true);
            files.add(world);
            worlds.put(name.substring(0, name.length() - 4), Config.fromValues(world.parsed().values(), global));
        }
        Map<String, String> defaults = new TreeMap<>();
        for (Phrase phrase : Phrase.values()) defaults.put(phrase.name(), Objects.requireNonNull(phrase.getPhrase(), "Language defaults must be loaded first"));
        var language = OperatorConfigStore.prepare(directory.resolve(ConfigFile.LANGUAGE), defaults, Map.of(), true, false);
        files.add(language);
        byte[] cache = OperatorConfigStore.read(directory.resolve(ConfigFile.LANGUAGE_CACHE));
        Map<String, String> translations = new LinkedHashMap<>();
        if (cache != null) {
            // Runtime translation cache has no operator schema. Read it without activating any phrases.
            ConfigFile.load(new java.io.ByteArrayInputStream(cache), translations, true);
        }
        byte[] blacklist = OperatorConfigStore.read(directory.resolve(ConfigHandler.BLACKLIST_FILENAME));
        return new PreparedConfiguration(directory, names, files, global, worlds, language.parsed().values(), cache, translations, blacklist, ConfigHandler.prepareBlacklist(blacklist));
    }

    private static Set<String> worldNames(Path directory) throws IOException {
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) return Set.of();
        Set<String> result = new TreeSet<>();
        try (var entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (!name.startsWith(".") && name.endsWith(".yml") && !name.equals(ConfigFile.CONFIG) && !name.equals(ConfigFile.LANGUAGE)) {
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) throw new IOException("World configuration is not a regular file");
                    result.add(name);
                }
            }
        }
        return result;
    }

    void recheck() throws IOException {
        if (!worldNames.equals(worldNames(directory))) throw new IOException("World configuration membership changed during preparation");
        for (var file : files) file.recheck();
        if (!Arrays.equals(blacklistSource, OperatorConfigStore.read(directory.resolve(ConfigHandler.BLACKLIST_FILENAME)))) throw new IOException("Blacklist changed during preparation");
        if (!Arrays.equals(translationSource, OperatorConfigStore.read(directory.resolve(ConfigFile.LANGUAGE_CACHE)))) throw new IOException("Language cache changed during preparation");
    }

    void activate(boolean reload) throws IOException {
        synchronized (ConfigFile.LANGUAGE_LOCK) {
            if (reload && !Config.getGlobal().sameDatabaseSettings(global)) throw new IOException("Database settings changed; restart required. Current configuration and database retained.");
            recheck();
            for (var file : files) file.install();
            // All sources, including already-current files, are checked again after every migration install.
            if (!worldNames.equals(worldNames(directory))) throw new IOException("World configuration membership changed before activation");
            for (var file : files) file.verifyInstalled();
            if (!Arrays.equals(translationSource, OperatorConfigStore.read(directory.resolve(ConfigFile.LANGUAGE_CACHE)))) throw new IOException("Language cache changed before activation");
            if (!Arrays.equals(blacklistSource, OperatorConfigStore.read(directory.resolve(ConfigHandler.BLACKLIST_FILENAME)))) throw new IOException("Blacklist changed before activation");
            Language.State preparedLanguage = ConfigFile.prepareLanguage(language, translations);
            Config.publish(global, worlds, preparedLanguage, blacklist);
        }
    }
}

package net.coreprotect.config;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import net.coreprotect.CoreProtect;
import net.coreprotect.language.Language;
import net.coreprotect.language.Phrase;
import net.coreprotect.utility.ErrorReporter;

public class ConfigFile {

    public static final String CONFIG = "config.yml";
    public static final String LANGUAGE = "language.yml";
    public static final String LANGUAGE_CACHE = ".language";

    public static final Object LANGUAGE_LOCK = new Object();
    private static final AtomicLong LANGUAGE_EPOCH = new AtomicLong();
    private static final TreeMap<String, String> DEFAULT_VALUES = new TreeMap<>();
    private static final TreeMap<String, String> USER_VALUES = new TreeMap<>();
    private static final String DEFAULT_FILE_HEADER = "# CoreProtect Language File (en)";
    private final HashMap<String, String> lang;

    public static long getLanguageEpoch() {
        return LANGUAGE_EPOCH.get();
    }

    public static void init(String fileName) throws IOException {
        synchronized (LANGUAGE_LOCK) {
            boolean isCache = fileName.startsWith(".");
            if (!isCache) {
                Config.init();
                return;
            }
            for (Phrase phrase : Phrase.values()) {
                DEFAULT_VALUES.put(phrase.name(), phrase.getPhrase());
                USER_VALUES.put(phrase.name(), phrase.getUserPhrase());
            }

            loadFiles(fileName, isCache);
        }
    }

    static Language.State prepareLanguage(Map<String, String> operator, Map<String, String> cache) {
        Map<Phrase, String> users = new java.util.EnumMap<>(Phrase.class);
        Map<Phrase, String> translated = new java.util.EnumMap<>(Phrase.class);
        DEFAULT_VALUES.clear(); USER_VALUES.clear();
        for (Phrase phrase : Phrase.values()) {
            String fallback = phrase.getPhrase();
            String selected = operator.getOrDefault(phrase.name(), fallback);
            if (selected.isEmpty()) selected = fallback; // legacy empty phrase means bundled fallback
            users.put(phrase, selected);
            String cached = cache.get(phrase.name());
            translated.put(phrase, selected.equals(fallback) && cached != null && !cached.isEmpty() ? cached : selected);
            DEFAULT_VALUES.put(phrase.name(), fallback);
            USER_VALUES.put(phrase.name(), selected);
        }
        LANGUAGE_EPOCH.incrementAndGet();
        return Language.prepare(users, translated);
    }

    public ConfigFile() {
        this.lang = new LinkedHashMap<>();
    }

    public void load(final InputStream in, String fileName, boolean isCache) throws IOException {
        // if we fail reading, we will not corrupt our current config.
        final Map<String, String> newConfig = new LinkedHashMap<>(this.lang.size());
        ConfigFile.load(in, newConfig, true);

        this.lang.clear();
        this.lang.putAll(newConfig);

        synchronized (LANGUAGE_LOCK) {
            for (final Entry<String, String> entry : this.lang.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (DEFAULT_VALUES.containsKey(key) && value.length() > 0 && (!isCache || DEFAULT_VALUES.get(key).equals(USER_VALUES.get(key)))) {
                    Phrase phrase = Phrase.valueOf(key);
                    if (!isCache) {
                        Language.setUserPhrase(phrase, value);
                    }

                    Language.setTranslatedPhrase(phrase, value);
                }
            }
        }
    }

    // this function will close in
    public static void load(final InputStream in, final Map<String, String> config, boolean forceCase) throws IOException {
        try (final InputStream in0 = in) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            boolean firstLine = true;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (firstLine) {
                    firstLine = false;
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                        line = line.substring(1);
                    }
                }
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                    continue;
                }

                final int split = line.indexOf(':');

                if (split == -1) {
                    continue;
                }

                String key = line.substring(0, split).trim();
                String rawValue = line.substring(split + 1);
                if (!forceCase) {
                    int valueStart = 0;
                    while (valueStart < rawValue.length() && Character.isWhitespace(rawValue.charAt(valueStart))) {
                        valueStart++;
                    }
                    int commentIndex = DatabaseConfigWriter.findComment(rawValue, valueStart);
                    if (commentIndex >= 0) {
                        rawValue = rawValue.substring(0, commentIndex);
                    }
                }
                String value = unescapeValue(rawValue.trim(), forceCase);

                if (forceCase) {
                    key = key.toUpperCase(Locale.ROOT);
                }
                config.put(key, value);
            }

            reader.close();
        }
    }

    private static String unescapeValue(String value, boolean unescapeLineBreaks) {
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            String inner = value.substring(1, value.length() - 1);
            StringBuilder sb = new StringBuilder(inner.length());
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\'' && i + 1 < inner.length() && inner.charAt(i + 1) == '\'') {
                    sb.append('\'');
                    i++;
                }
                else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            String inner = value.substring(1, value.length() - 1);
            StringBuilder sb = new StringBuilder(inner.length());
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\\' && i + 1 < inner.length()) {
                    char next = inner.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        sb.append(next);
                        i++;
                    }
                    else if (unescapeLineBreaks && next == 'n') {
                        sb.append('\n');
                        i++;
                    }
                    else if (unescapeLineBreaks && next == 'r') {
                        sb.append('\r');
                        i++;
                    }
                    else {
                        sb.append(c);
                    }
                }
                else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return value;
    }

    private static Map<String, byte[]> loadFiles(String fileName, boolean isCache) throws IOException {
        final CoreProtect plugin = CoreProtect.getInstance();
        final File configFolder = plugin.getDataFolder();
        if (!configFolder.exists()) {
            configFolder.mkdirs();
        }

        final Map<String, byte[]> map = new HashMap<>();
        final File globalFile = new File(configFolder, fileName);

        if (globalFile.exists()) {
            // we always add options to the global config
            final byte[] data = Files.readAllBytes(globalFile.toPath());
            map.put("config", data);

            final ConfigFile temp = new ConfigFile();
            temp.load(new ByteArrayInputStream(data), fileName, isCache);
            temp.addMissingOptions(globalFile);
        }
        else {
            final ConfigFile temp = new ConfigFile();
            temp.addMissingOptions(globalFile);
        }

        return map;
    }

    public static String escapeQuotedValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
    }

    public void addMissingOptions(final File file) throws IOException {
        if (file.getName().startsWith(".")) return;
        var prepared = OperatorConfigStore.prepare(file.toPath(), DEFAULT_VALUES, java.util.Map.of(), true, false);
        prepared.install();
        prepared.verifyInstalled();
    }

    public static void modifyLine(String fileName, String oldLine, String newLine) {
        try {
            Path path = Paths.get(ConfigHandler.path).resolve(fileName);
            if (!Files.isRegularFile(path)) {
                return;
            }
            byte[] expected = OperatorConfigStore.read(path);
            // Schema adoption already owns the complete current language template. Old DB patches must not rewrite it.
            if (fileName.equals(LANGUAGE) && OperatorConfigStore.parse(expected, true).version() > 0) return;
            List<String> lines = new java.util.ArrayList<>(java.util.Arrays.asList(new String(expected, StandardCharsets.UTF_8).split("\\R")));

            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).equalsIgnoreCase(oldLine)) {
                    if (newLine != null && newLine.length() > 0) {
                        lines.set(i, newLine);
                    }
                    else {
                        lines.remove(i);
                    }

                    break;
                }
            }

            String content = String.join(Config.LINE_SEPARATOR, lines);
            OperatorConfigStore.replace(path, expected, content.getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }
    }

    public static void sortFile(String fileName) {
        try {
            Path path = Paths.get(ConfigHandler.path).resolve(fileName);
            if (!Files.isRegularFile(path)) {
                return;
            }
            byte[] expected = OperatorConfigStore.read(path);
            // Schema adoption already owns the complete current language template. Old DB patches must not rewrite it.
            if (fileName.equals(LANGUAGE) && OperatorConfigStore.parse(expected, true).version() > 0) return;
            List<String> lines = new java.util.ArrayList<>(java.util.Arrays.asList(new String(expected, StandardCharsets.UTF_8).split("\\R")));
            if (lines.size() > 2) {
                Collections.sort(lines.subList(2, lines.size()));
            }

            String content = String.join(Config.LINE_SEPARATOR, lines);
            OperatorConfigStore.replace(path, expected, content.getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception e) {
            ErrorReporter.report(e);
        }
    }

    public static void resetCache(String cacheName, String fileName) throws IOException {
        synchronized (LANGUAGE_LOCK) {
            LANGUAGE_EPOCH.incrementAndGet();
            File file = new File(CoreProtect.getInstance().getDataFolder(), cacheName);
            if (file.length() > 0) {
                DatabaseConfigWriter.writeAtomically(file.toPath(), new byte[0]);
                Config.publishLanguage(prepareLanguage(new java.util.TreeMap<>(USER_VALUES), java.util.Map.of()));
            }
        }
    }
}

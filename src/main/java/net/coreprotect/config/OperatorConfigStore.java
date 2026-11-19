package net.coreprotect.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Schema one for the deliberately flat CoreProtect operator format; never a runtime-store writer. */
final class OperatorConfigStore {
    static final int VERSION = 1;
    record Parsed(int version, LinkedHashMap<String, String> values) {}
    record Prepared(Path path, byte[] original, byte[] candidate, Parsed parsed, boolean language) {
        void recheck() throws IOException {
            if (!Arrays.equals(original, read(path))) throw new IOException("Configuration changed during preparation: " + path.getFileName());
        }
        void install() throws IOException {
            recheck();
            if (!Arrays.equals(original, candidate)) replace(path, original, candidate);
        }
        void verifyInstalled() throws IOException {
            if (!Arrays.equals(candidate, read(path))) throw new IOException("Configuration changed before activation: " + path.getFileName());
            parse(candidate, language);
        }
    }

    static byte[] read(Path path) throws IOException {
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Configuration is not a regular file: " + path.getFileName());
        return Files.readAllBytes(path);
    }

    static Prepared prepare(Path path, Map<String, String> template, Map<String, String[]> headers, boolean language, boolean world) throws IOException {
        byte[] original = read(path);
        Parsed source = parse(original == null ? new byte[0] : original, language);
        LinkedHashMap<String, String> candidate = new LinkedHashMap<>();
        if (!world) candidate.putAll(template);
        // Before database-type existed, an installed configuration selected SQLite unless use-mysql was true.
        if (!language && !world && original != null && !source.values.containsKey("database-type")) {
            candidate.put("database-type", "true".equalsIgnoreCase(source.values.get("use-mysql")) ? "mysql" : "sqlite");
        }
        // Known siblings use template order; world files remain sparse so global overrides still inherit.
        for (String key : template.keySet()) if (source.values.containsKey(key)) candidate.put(key, source.values.get(key));
        source.values.forEach(candidate::putIfAbsent);
        byte[] encoded = source.version == VERSION ? original : render(candidate, headers, language);
        if (encoded == null) encoded = render(candidate, headers, language);
        Parsed checked = parse(encoded, language);
        return new Prepared(path, original, encoded, checked, language);
    }

    static Parsed parse(byte[] bytes, boolean language) throws IOException {
        String content;
        try { content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (Exception e) { throw new IOException("Configuration is not valid UTF-8"); }
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') throw new IOException("Invalid control character in configuration");
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        int version = 0;
        int number = 0;
        for (String line : content.split("\\R", -1)) {
            number++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon < 1 || Character.isWhitespace(line.charAt(0))) throw invalid(number);
            String key = line.substring(0, colon).trim();
            if (!key.matches("[A-Za-z0-9_.-]+")) throw invalid(number);
            String raw = line.substring(colon + 1).trim();
            int comment = DatabaseConfigWriter.findComment(raw, 0);
            if (comment >= 0 && (!language || key.equals("config-version"))) raw = raw.substring(0, comment).trim();
            boolean marker = key.equalsIgnoreCase("config-version");
            if (language && !marker) key = key.toUpperCase(Locale.ROOT);
            if (!seen.add(marker ? "config-version" : key)) throw invalid(number);
            if (marker) {
                if (!key.equals("config-version") || !raw.matches("0|[1-9][0-9]*")) throw invalid(number);
                try { version = Integer.parseInt(raw); } catch (NumberFormatException e) { throw invalid(number); }
                if (version > VERSION) throw new IOException("Unsupported config-version; supported=1, installed=" + version);
                continue;
            }
            if (raw.isEmpty()) {
                // Published legacy templates deliberately used bare empty strings for these options.
                if (!language && !Set.of("donation-key", "mysql-password", "clickhouse-password", "sqlite-database", "auto-purge-time").contains(key)) throw invalid(number);
                values.put(key, "");
                continue;
            }
            String value = decode(raw, number, language);
            values.put(key, value);
        }
        if (!language) validateValues(values);
        return new Parsed(version, values);
    }

    private static String decode(String raw, int line, boolean language) throws IOException {
        char quote = raw.charAt(0);
        if (quote != '\'' && quote != '"') {
            if ("~".equals(raw) || "null".equalsIgnoreCase(raw) || "[{&*!|>%".indexOf(quote) >= 0 || raw.equals("---") || raw.equals("...") || raw.startsWith("- ") || raw.contains(": ")) throw invalid(line);
            return raw;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == quote) {
                if (quote == '\'' && i + 1 < raw.length() && raw.charAt(i + 1) == '\'') { result.append('\''); i++; continue; }
                if (i != raw.length() - 1) throw invalid(line);
                return result.toString();
            }
            if (quote == '"' && c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                if (next == '\\' || next == '"') { result.append(next); i++; continue; }
                if (language && (next == 'n' || next == 'r')) { result.append(next == 'n' ? '\n' : '\r'); i++; continue; }
            }
            result.append(c);
        }
        throw invalid(line);
    }

    private static IOException invalid(int line) { return new IOException("Invalid or ambiguous flat configuration entry at line " + line + "; config-version supported=1"); }

    static void validateValues(Map<String, String> values) throws IOException {
        for (var entry : values.entrySet()) {
            String key = entry.getKey(), value = entry.getValue();
            String fallback = Config.template().get(key);
            if (("true".equals(fallback) || "false".equals(fallback)) && !key.equals("auto-purge") || Config.BOOLEAN_OPTIONS.contains(key)) {
                if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) throw new IOException("Invalid boolean setting: " + key);
            }
            if (Config.INTEGER_OPTIONS.contains(key)) {
                int parsed;
                try { parsed = Integer.parseInt(value); } catch (NumberFormatException e) { throw new IOException("Invalid integer setting: " + key); }
                if (parsed < 0 || key.endsWith("-port") && (parsed < 1 || parsed > 65535) || (key.equals("maximum-pool-size") || key.equals("duckdb-threads")) && parsed < 1) throw new IOException("Invalid integer range: " + key);
            }
        }
        if (values.containsKey("database-type") && !Set.of("mysql", "sqlite", "duckdb", "clickhouse").contains(values.get("database-type").toLowerCase(Locale.ROOT))) throw new IOException("Invalid database-type");
        if (values.containsKey("table-prefix") && !values.get("table-prefix").matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IOException("Invalid table-prefix");
        String sqlite = values.get("sqlite-database");
        if (sqlite != null && (sqlite.indexOf('/') >= 0 || sqlite.indexOf('\\') >= 0 || sqlite.indexOf('\0') >= 0 || sqlite.indexOf(':') >= 0 || sqlite.equals(".") || sqlite.equals(".."))) throw new IOException("Invalid sqlite-database filename");
    }

    private static byte[] render(Map<String, String> values, Map<String, String[]> headers, boolean language) {
        StringBuilder out = new StringBuilder(language ? "# CoreProtect Language File (en)\n" : "# CoreProtect Config\n");
        out.append("config-version: 1\n");
        values.forEach((key, value) -> {
            String[] comments = headers.get(key);
            if (comments != null) { out.append('\n'); for (String comment : comments) out.append(comment).append('\n'); }
            out.append(key).append(": \"").append(ConfigFile.escapeQuotedValue(value)).append("\"\n");
        });
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static void replace(Path target, byte[] expected, byte[] candidate) throws IOException {
        Path absolute = target.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".coreprotect-config-", ".tmp");
        try {
            PosixFileAttributeView posix = Files.getFileAttributeView(temporary, PosixFileAttributeView.class);
            AclFileAttributeView acl = Files.getFileAttributeView(temporary, AclFileAttributeView.class);
            if (posix != null) posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            else if (acl != null) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(temporary)).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
            else throw new IOException("Cannot restrict configuration candidate permissions");
            Files.write(temporary, candidate, StandardOpenOption.TRUNCATE_EXISTING);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            if (!Arrays.equals(candidate, Files.readAllBytes(temporary))) throw new IOException("Configuration candidate verification failed");
            if (!Arrays.equals(expected, read(target))) throw new IOException("Configuration changed before replacement: " + target.getFileName());
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            if (!Arrays.equals(candidate, read(target))) throw new IOException("Configuration replacement verification failed");
        } finally { Files.deleteIfExists(temporary); }
    }
}

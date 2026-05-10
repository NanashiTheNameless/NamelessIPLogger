package dev.namelessnanashi.namelessiplogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

public final class PluginStrings {
	private static final String FILE_NAME = "strings.yml";
	private static final PluginStrings DEFAULT_INSTANCE = new PluginStrings(defaultValues());

	private final Map<String, String> values;

	private PluginStrings(final Map<String, String> values) {
		this.values = Map.copyOf(values);
	}

	public static PluginStrings defaults() {
		return DEFAULT_INSTANCE;
	}

	public static PluginStrings load(final Path dataDirectory, final ComponentLogger logger) throws IOException {
		Files.createDirectories(dataDirectory);
		final Path stringsFile = dataDirectory.resolve(FILE_NAME);
		if (Files.notExists(stringsFile)) {
			Files.writeString(stringsFile, defaultStringsFile(defaultValues()), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
			logger.info("Created default strings file at {}", stringsFile.toAbsolutePath().toString());
			return DEFAULT_INSTANCE;
		}

		final Map<String, String> loadedValues = parseSimpleYaml(stringsFile);
		final Map<String, String> mergedValues = new LinkedHashMap<>(defaultValues());
		for (final String key : defaultValues().keySet()) {
			final String loadedValue = loadedValues.get(key);
			if (loadedValue != null) {
				mergedValues.put(key, loadedValue);
			}
		}

		if (!loadedValues.keySet().containsAll(defaultValues().keySet())) {
			Files.writeString(
				stringsFile,
				defaultStringsFile(mergedValues),
				StandardCharsets.UTF_8,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE
			);
			logger.info("Updated strings file at {}", stringsFile.toAbsolutePath().toString());
		}

		return new PluginStrings(mergedValues);
	}

	public String get(final String key) {
		return values.getOrDefault(key, DEFAULT_INSTANCE.values.getOrDefault(key, key));
	}

	public Component component(final String key, final String... replacements) {
		return Component.text(format(key, replacements));
	}

	public String format(final String key, final String... replacements) {
		String message = get(key);
		for (int index = 0; index + 1 < replacements.length; index += 2) {
			final String replacementValue = replacements[index + 1] == null ? "" : replacements[index + 1];
			message = message.replace("{" + replacements[index] + "}", replacementValue);
		}
		return message;
	}

	private static Map<String, String> parseSimpleYaml(final Path stringsFile) throws IOException {
		final Map<String, String> values = new LinkedHashMap<>();
		for (final String rawLine : Files.readAllLines(stringsFile, StandardCharsets.UTF_8)) {
			final String line = rawLine.trim();
			if (line.isEmpty() || line.startsWith("#")) {
				continue;
			}

			final int splitIndex = line.indexOf(':');
			if (splitIndex <= 0) {
				continue;
			}

			final String key = line.substring(0, splitIndex).trim();
			String value = line.substring(splitIndex + 1).trim();
			if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
				value = value.substring(1, value.length() - 1);
			}
			values.put(key, unescapeConfigValue(value));
		}
		return values;
	}

	private static Map<String, String> defaultValues() {
		final Map<String, String> values = new LinkedHashMap<>();
		values.put("prefix", "[NamelessIPLogger]");
		values.put("access.console-only", "This command is console-only.");
		values.put("access.permission-required", "This command requires {permission}.");
		values.put("usage.lookup", "Usage: /niplookup <uuid|username|ip> <value>");
		values.put("usage.reload", "Usage: /niplookup reload");
		values.put("usage.updatedb", "Usage: /niplookup updatedb");
		values.put("usage.checkupdates", "Usage: /niplookup checkupdates");
		values.put("reload.success", "Configuration reloaded successfully.");
		values.put("reload.failure", "Reload failed: {error}");
		values.put("geoip.update.start", "Updating GeoIP databases now...");
		values.put("geoip.update.success", "GeoIP databases were updated successfully.");
		values.put("geoip.update.failure", "Update failed: {error}");
		values.put("geoip.service-unavailable", "GeoIP service is not initialized yet.");
		values.put("updates.check.start", "Checking for updates now...");
		values.put("updates.checker-unavailable", "Update checker is not initialized yet.");
		values.put("updates.endpoint-blocked", "Update check skipped because endpoint resolved to a private, link-local, loopback, or otherwise unsafe address: {endpoint}");
		values.put("updates.http-failure", "Update check failed with HTTP status {status}.");
		values.put("updates.no-release", "No applicable stable GitHub releases were found.");
		values.put("updates.available", "Update available: current={current} latest={latest} {url}");
		values.put("updates.current", "NamelessIPLogger is up to date. Current version: {current}.");
		values.put("updates.interrupted", "Update check interrupted.");
		values.put("updates.failure", "Update check failed: {error}");
		values.put("lookup.invalid-uuid", "Invalid UUID: {uuid}");
		values.put("lookup.no-records.uuid", "No records found for UUID {uuid}");
		values.put("lookup.no-records.username", "No records found for username {username}");
		values.put("lookup.no-records.ip", "No records found for IP {ip}");
		values.put("lookup.matches.username", "Matches for username '{username}': {count}");
		values.put("lookup.matches.ip", "Matches for IP {ip}: {count}");
		values.put("lookup.player", "Player uuid={uuid} username={username} firstSeen={first_seen} lastSeen={last_seen} lastIp={last_ip}");
		values.put("lookup.no-ip-links", "  No IP links recorded.");
		values.put("lookup.linked-ips", "  Linked IPs: {count}");
		values.put("lookup.ip-link", "  - ip={ip} firstSeen={first_seen} lastSeen={last_seen} timesSeen={times_seen}");
		values.put("lookup.ip-correlation", "- uuid={uuid} username={username} firstSeen={first_seen} lastSeen={last_seen} timesSeen={times_seen}");
		values.put("lookup.geo", "  geo={geo}");
		values.put("lookup.geo.indented", "    geo={geo}");
		values.put("lookup.geo-summary", "status={status} city={city} region={region} country={country} countryCode={country_code} timezone={timezone} lat={lat} lon={lon} message={message}");
		return values;
	}

	private static String defaultStringsFile(final Map<String, String> values) {
		final StringBuilder builder = new StringBuilder()
			.append("# ============================================================================\n")
			.append("# NamelessIPLogger - strings.yml\n")
			.append("# ============================================================================\n")
			.append("# Edit these messages to translate command and chat output.\n")
			.append("# Keep placeholder names such as {uuid}, {error}, and {permission} intact.\n")
			.append("# Quote values to avoid YAML parser edge cases.\n\n");
		for (final Map.Entry<String, String> entry : values.entrySet()) {
			builder.append(entry.getKey()).append(": ").append(quoteConfigValue(entry.getValue())).append("\n");
		}
		return builder.toString();
	}

	private static String quoteConfigValue(final String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
	}

	private static String unescapeConfigValue(final String value) {
		return value.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
	}
}

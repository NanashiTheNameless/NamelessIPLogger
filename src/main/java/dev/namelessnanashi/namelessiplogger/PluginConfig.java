package dev.namelessnanashi.namelessiplogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

public record PluginConfig(
	GeoIpProvider geoIpProvider,
	String maxMindAccountId,
	String maxMindLicenseKey,
	String dbIpUrl,
	String dbIpAsnUrl,
	String maxMindUrlTemplate,
	String maxMindEditionId,
	String maxMindAsnEditionId,
	int geoIpRefreshDays,
	boolean logIncludeUsername,
	boolean logIncludeIp,
	boolean logIncludeGeoIp,
	boolean logWritePlayers,
	boolean logWriteIpLinks,
	boolean logWriteConnectionEvents,
	int logMaxRetentionDays,
	boolean logConsoleConnect,
	boolean logConsoleDisconnect,
	boolean commandsAllowAdminPermission,
	boolean telemetryEnabled,
	boolean updateCheckEnabled,
	int updateCheckIntervalHours
) {
	private static final String DEFAULT_DBIP_URL = "https://download.db-ip.com/free/dbip-city-lite-YYYY-MM.mmdb.gz";
	private static final String DEFAULT_DBIP_ASN_URL = "https://download.db-ip.com/free/dbip-asn-lite-YYYY-MM.mmdb.gz";
	private static final String DEFAULT_MAXMIND_URL_TEMPLATE = "https://download.maxmind.com/geoip/databases/{edition_id}/download?suffix=tar.gz";
	private static final String DEFAULT_MAXMIND_EDITION = "GeoLite2-City";
	private static final int DEFAULT_REFRESH_DAYS = 7;
	private static final int CURRENT_CONFIG_VERSION = 2;
	private static final String CONFIG_VERSION_KEY = "config.version";

	public static PluginConfig load(final Path dataDirectory, final ComponentLogger logger) throws IOException {
		Files.createDirectories(dataDirectory);
		final Path configFile = dataDirectory.resolve("config.yml");
		if (Files.notExists(configFile)) {
			Files.writeString(configFile, defaultConfig(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
			logger.info("Created default config file at {}", configFile.toAbsolutePath().toString());
		}

		Map<String, String> properties = parseSimpleYaml(configFile);
		if (shouldMigrateConfig(properties)) {
			properties = migrateConfig(configFile, properties, logger);
		}

		final GeoIpProvider provider = GeoIpProvider.from(get(properties, "geoip.provider", "dbip"));
		final String maxMindAccountId = get(properties, "geoip.maxmind.account-id", "").trim();
		final String maxMindLicenseKey = get(properties, "geoip.maxmind.license-key", "").trim();
		final String dbIpUrl = get(properties, "geoip.dbip.url", DEFAULT_DBIP_URL).trim();
		final String dbIpAsnUrl = get(properties, "geoip.dbip.asn-url", DEFAULT_DBIP_ASN_URL).trim();
		final String maxMindUrlTemplate = get(properties, "geoip.maxmind.url-template", DEFAULT_MAXMIND_URL_TEMPLATE).trim();
		final String maxMindEditionId = get(properties, "geoip.maxmind.edition-id", DEFAULT_MAXMIND_EDITION).trim();
		final String maxMindAsnEditionId = get(properties, "geoip.maxmind.asn-edition-id", "GeoLite2-ASN").trim();
		final int refreshDays = parseRefreshDays(get(properties, "geoip.refresh-days", Integer.toString(DEFAULT_REFRESH_DAYS)));

		final boolean logIncludeUsername = parseBoolean(get(properties, "log.include-username", "true"), true);
		final boolean logIncludeIp = parseBoolean(get(properties, "log.include-ip", "true"), true);
		final boolean logIncludeGeoIp = parseBoolean(get(properties, "log.include-geoip", "true"), true);
		final boolean logWritePlayers = parseBoolean(get(properties, "log.write-players", "true"), true);
		final boolean logWriteIpLinks = parseBoolean(get(properties, "log.write-ip-links", "true"), true);
		final boolean logWriteConnectionEvents = parseBoolean(get(properties, "log.write-connection-events", "true"), true);
		final int logMaxRetentionDays = parseMaxRetentionDays(get(properties, "log.max-retention-days", "0"));
		final boolean logConsoleConnect = parseBoolean(get(properties, "log.console-connect", "true"), true);
		final boolean logConsoleDisconnect = parseBoolean(get(properties, "log.console-disconnect", "true"), true);
		final boolean commandsAllowAdminPermission = parseBoolean(get(properties, "commands.allow-admin-permission", "false"), false);
		final boolean telemetryEnabled = parseBoolean(get(properties, "telemetry.enabled", "true"), true);
		final boolean updateCheckEnabled = parseBoolean(get(properties, "updates.check-enabled", "true"), true);
		final int updateCheckIntervalHours = parseUpdateCheckIntervalHours(get(properties, "updates.check-interval-hours", "6"));

		return new PluginConfig(
			provider,
			maxMindAccountId,
			maxMindLicenseKey,
			dbIpUrl,
			dbIpAsnUrl,
			maxMindUrlTemplate,
			maxMindEditionId,
			maxMindAsnEditionId,
			refreshDays,
			logIncludeUsername,
			logIncludeIp,
			logIncludeGeoIp,
			logWritePlayers,
			logWriteIpLinks,
			logWriteConnectionEvents,
			logMaxRetentionDays,
			logConsoleConnect,
			logConsoleDisconnect,
			commandsAllowAdminPermission,
			telemetryEnabled,
			updateCheckEnabled,
			updateCheckIntervalHours
		);
	}

	private static boolean parseBoolean(final String value, final boolean defaultValue) {
		if (value == null) {
			return defaultValue;
		}
		final String normalized = value.trim().toLowerCase(Locale.ROOT);
		if ("true".equals(normalized)) {
			return true;
		}
		if ("false".equals(normalized)) {
			return false;
		}
		return defaultValue;
	}

	private static Map<String, String> parseSimpleYaml(final Path configFile) throws IOException {
		final Map<String, String> values = new LinkedHashMap<>();
		for (final String rawLine : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
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
			values.put(key, value);
		}
		return values;
	}

	private static String get(final Map<String, String> values, final String key, final String defaultValue) {
		final String value = values.get(key);
		if (value == null) {
			return defaultValue;
		}
		return value;
	}

	private static int parseRefreshDays(final String rawValue) {
		try {
			final int parsed = Integer.parseInt(rawValue.trim());
			return Math.max(parsed, 1);
		} catch (final NumberFormatException exception) {
			return DEFAULT_REFRESH_DAYS;
		}
	}

	private static int parseMaxRetentionDays(final String rawValue) {
		try {
			final int parsed = Integer.parseInt(rawValue.trim());
			return Math.max(parsed, 0);
		} catch (final NumberFormatException exception) {
			return 0;
		}
	}

	private static int parseUpdateCheckIntervalHours(final String rawValue) {
		try {
			final int parsed = Integer.parseInt(rawValue.trim());
			return Math.max(parsed, 1);
		} catch (final NumberFormatException exception) {
			return 6;
		}
	}

	private static boolean shouldMigrateConfig(final Map<String, String> values) {
		final String rawVersion = values.get(CONFIG_VERSION_KEY);
		if (rawVersion == null || rawVersion.isBlank()) {
			return true;
		}

		try {
			if (Integer.parseInt(rawVersion.trim()) != CURRENT_CONFIG_VERSION) {
				return true;
			}
		} catch (final NumberFormatException exception) {
			return true;
		}

		for (final String key : defaultValues().keySet()) {
			if (!values.containsKey(key) || !isKnownConfigValueValid(key, values.get(key))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isKnownConfigValueValid(final String key, final String value) {
		if (value == null) {
			return false;
		}
		return switch (key) {
			case CONFIG_VERSION_KEY -> isCurrentConfigVersion(value);
			case "geoip.provider" -> isGeoIpProviderValue(value);
			case "geoip.refresh-days" -> isIntegerAtLeast(value, 1);
			case "log.max-retention-days" -> isIntegerAtLeast(value, 0);
			case "updates.check-interval-hours" -> isIntegerAtLeast(value, 1);
			case "log.include-username",
				"log.include-ip",
				"log.include-geoip",
				"log.write-players",
				"log.write-ip-links",
				"log.write-connection-events",
				"log.console-connect",
				"log.console-disconnect",
				"commands.allow-admin-permission",
				"telemetry.enabled",
				"updates.check-enabled" -> isBooleanValue(value);
			default -> true;
		};
	}

	private static boolean isCurrentConfigVersion(final String value) {
		try {
			return Integer.parseInt(value.trim()) == CURRENT_CONFIG_VERSION;
		} catch (final NumberFormatException exception) {
			return false;
		}
	}

	private static boolean isGeoIpProviderValue(final String value) {
		final String normalized = value.trim().toLowerCase(Locale.ROOT);
		return "dbip".equals(normalized)
			|| "maxmind-geolite2".equals(normalized)
			|| "maxmind".equals(normalized)
			|| "geolite2".equals(normalized);
	}

	private static boolean isBooleanValue(final String value) {
		final String normalized = value.trim().toLowerCase(Locale.ROOT);
		return "true".equals(normalized)
			|| "false".equals(normalized);
	}

	private static boolean isIntegerAtLeast(final String value, final int minimum) {
		try {
			return Integer.parseInt(value.trim()) >= minimum;
		} catch (final NumberFormatException exception) {
			return false;
		}
	}

	private static Map<String, String> migrateConfig(
		final Path configFile,
		final Map<String, String> existingValues,
		final ComponentLogger logger
	) throws IOException {
		final Path backupFile = backupFileFor(configFile, existingValues);
		Files.copy(configFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
		logger.info("Backed up previous config file to {}", backupFile.toAbsolutePath().toString());

		final Map<String, String> defaults = defaultValues();
		final Map<String, String> mergedValues = new LinkedHashMap<>(defaults);
		for (final String key : defaults.keySet()) {
			final String existingValue = existingValues.get(key);
			if (!CONFIG_VERSION_KEY.equals(key) && existingValue != null && isKnownConfigValueValid(key, existingValue)) {
				mergedValues.put(key, existingValue);
			}
		}
		mergedValues.put(CONFIG_VERSION_KEY, Integer.toString(CURRENT_CONFIG_VERSION));

		Files.writeString(
			configFile,
			configFromValues(mergedValues, existingValues),
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE
		);
		logger.info("Updated config file at {} to schema version {}", configFile.toAbsolutePath().toString(), CURRENT_CONFIG_VERSION);
		return parseSimpleYaml(configFile);
	}

	private static Path backupFileFor(final Path configFile, final Map<String, String> existingValues) {
		final String rawVersion = existingValues.get(CONFIG_VERSION_KEY);
		if (rawVersion == null || rawVersion.isBlank()) {
			return configFile.resolveSibling(configFile.getFileName() + ".bak");
		}

		try {
			final int schemaVersion = Integer.parseInt(rawVersion.trim());
			return configFile.resolveSibling(configFile.getFileName() + "." + schemaVersion + ".bak");
		} catch (final NumberFormatException exception) {
			return configFile.resolveSibling(configFile.getFileName() + ".bak");
		}
	}

	private static Map<String, String> defaultValues() {
		final Map<String, String> values = new LinkedHashMap<>();
		values.put(CONFIG_VERSION_KEY, Integer.toString(CURRENT_CONFIG_VERSION));
		values.put("geoip.provider", "dbip");
		values.put("geoip.refresh-days", Integer.toString(DEFAULT_REFRESH_DAYS));
		values.put("geoip.dbip.url", DEFAULT_DBIP_URL);
		values.put("geoip.dbip.asn-url", DEFAULT_DBIP_ASN_URL);
		values.put("geoip.maxmind.account-id", "");
		values.put("geoip.maxmind.license-key", "");
		values.put("geoip.maxmind.edition-id", DEFAULT_MAXMIND_EDITION);
		values.put("geoip.maxmind.asn-edition-id", "GeoLite2-ASN");
		values.put("geoip.maxmind.url-template", DEFAULT_MAXMIND_URL_TEMPLATE);
		values.put("log.include-username", "true");
		values.put("log.include-ip", "true");
		values.put("log.include-geoip", "true");
		values.put("log.write-players", "true");
		values.put("log.write-ip-links", "true");
		values.put("log.write-connection-events", "true");
		values.put("log.max-retention-days", "0");
		values.put("log.console-connect", "true");
		values.put("log.console-disconnect", "true");
		values.put("commands.allow-admin-permission", "false");
		values.put("telemetry.enabled", "true");
		values.put("updates.check-enabled", "true");
		values.put("updates.check-interval-hours", "6");
		return values;
	}

	private static String defaultConfig() {
		return configFromValues(defaultValues(), Map.of());
	}

	private static String configFromValues(final Map<String, String> values, final Map<String, String> existingValues) {
		final StringBuilder unknownValues = new StringBuilder();
		for (final Map.Entry<String, String> entry : existingValues.entrySet()) {
			if (!values.containsKey(entry.getKey())) {
				if (unknownValues.isEmpty()) {
					unknownValues
						.append("\n")
						.append("# --------------------------------------------------------------------------\n")
						.append("# Unrecognized settings preserved from the previous config\n")
						.append("# --------------------------------------------------------------------------\n")
						.append("# These keys are not read by this plugin version, but were kept during migration.\n");
				}
				unknownValues.append(entry.getKey()).append(": ").append(quoteConfigValue(entry.getValue())).append("\n");
			}
		}

		return "# ============================================================================\n"
			+ "# NamelessIPLogger - config.yml\n"
			+ "# ============================================================================\n"
			+ "# All keys are flat key:value pairs (no nested YAML objects required).\n"
			+ "#\n"
			+ "# Tips:\n"
			+ "# - Keep this file UTF-8 encoded.\n"
			+ "# - Apply edits with /niplookup reload (proxy restart is not required).\n"
			+ "# - Run /niplookup updatedb to force immediate GeoIP database redownload.\n"
			+ "# - Quote values to avoid YAML parser edge cases.\n"
			+ "#\n"
			+ "# Config schema version. Do not edit.\n"
			+ "# The plugin uses this to migrate older configs while preserving known settings.\n"
			+ CONFIG_VERSION_KEY + ": " + quoteConfigValue(values.get(CONFIG_VERSION_KEY)) + "\n"
			+ "\n"
			+ "# Main provider for GeoIP database downloads/lookups.\n"
			+ "# Allowed values:\n"
			+ "#   - dbip\n"
			+ "#   - maxmind-geolite2\n"
			+ "#\n"
			+ "# Provider guidance:\n"
			+ "# - dbip: easiest setup, no license key required.\n"
			+ "# - maxmind-geolite2: requires a MaxMind key, generally better data quality.\n"
			+ "geoip.provider: " + quoteConfigValue(values.get("geoip.provider")) + "\n"
			+ "\n"
			+ "# Refresh interval in days for database redownload attempts.\n"
			+ "# Minimum effective value is 1.\n"
			+ "# Lower values update data sooner but increase network usage.\n"
			+ "geoip.refresh-days: " + quoteConfigValue(values.get("geoip.refresh-days")) + "\n"
			+ "\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# DB-IP provider settings\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# YYYY-MM is expanded at runtime to the current year-month.\n"
			+ "# The configured URL remains the source of truth.\n"
			+ "# If download fails, plugin only tries .mmdb/.mmdb.gz extension fallback.\n"
			+ "# Compatibility: URLs using -latest also try current/recent monthly filenames.\n"
			+ "# DB-IP City MMDB gzip source (.mmdb.gz expected).\n"
			+ "geoip.dbip.url: " + quoteConfigValue(values.get("geoip.dbip.url")) + "\n"
			+ "# DB-IP ASN MMDB gzip source (.mmdb.gz expected).\n"
			+ "geoip.dbip.asn-url: " + quoteConfigValue(values.get("geoip.dbip.asn-url")) + "\n"
			+ "# If you host your own DB-IP mirror, set these to your mirror endpoints.\n"
			+ "\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# MaxMind GeoLite2 provider settings\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# License key from https://www.maxmind.com/\n"
			+ "# Required for maxmind-geolite2.\n"
			+ "# Account ID is optional but recommended for direct-download basic auth.\n"
			+ "geoip.maxmind.account-id: " + quoteConfigValue(values.get("geoip.maxmind.account-id")) + "\n"
			+ "geoip.maxmind.license-key: " + quoteConfigValue(values.get("geoip.maxmind.license-key")) + "\n"
			+ "# Keep the license key secret; do not share logs/screenshots with this value.\n"
			+ "# City edition id. Usually keep as GeoLite2-City.\n"
			+ "geoip.maxmind.edition-id: " + quoteConfigValue(values.get("geoip.maxmind.edition-id")) + "\n"
			+ "# ASN edition id. Usually keep as GeoLite2-ASN.\n"
			+ "geoip.maxmind.asn-edition-id: " + quoteConfigValue(values.get("geoip.maxmind.asn-edition-id")) + "\n"
			+ "# Download URL template used for MaxMind edition downloads.\n"
			+ "# Placeholders:\n"
			+ "#   {edition_id}  -> replaced with edition id\n"
			+ "#   {license_key} -> replaced with license key (optional compatibility)\n"
			+ "# If account-id is set, basic auth is sent as account-id:license-key.\n"
			+ "geoip.maxmind.url-template: " + quoteConfigValue(values.get("geoip.maxmind.url-template")) + "\n"
			+ "\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# Logging controls (data collection + persistence + console output)\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# Privacy note: username/IP/GeoIP toggles affect what is written to disk.\n"
			+ "# Warning: disabling log.include-ip can reduce correlation usefulness.\n"
			+ "# Include username in stored records/events.\n"
			+ "log.include-username: " + quoteConfigValue(values.get("log.include-username")) + "\n"
			+ "# Include IP in stored records/events.\n"
			+ "log.include-ip: " + quoteConfigValue(values.get("log.include-ip")) + "\n"
			+ "# Perform and store GeoIP enrichment details.\n"
			+ "log.include-geoip: " + quoteConfigValue(values.get("log.include-geoip")) + "\n"
			+ "# Write/update players.tsv\n"
			+ "log.write-players: " + quoteConfigValue(values.get("log.write-players")) + "\n"
			+ "# Write/update ip_links.tsv\n"
			+ "log.write-ip-links: " + quoteConfigValue(values.get("log.write-ip-links")) + "\n"
			+ "# Append to connection_events.tsv\n"
			+ "log.write-connection-events: " + quoteConfigValue(values.get("log.write-connection-events")) + "\n"
			+ "# Maximum age (in days) to retain log rows based on event/last_seen time.\n"
			+ "# 0 disables retention pruning.\n"
			+ "log.max-retention-days: " + quoteConfigValue(values.get("log.max-retention-days")) + "\n"
			+ "# Print connect logs to proxy console\n"
			+ "log.console-connect: " + quoteConfigValue(values.get("log.console-connect")) + "\n"
			+ "# Print disconnect logs to proxy console\n"
			+ "log.console-disconnect: " + quoteConfigValue(values.get("log.console-disconnect")) + "\n"
			+ "\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# Command access\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# By default, /niplookup commands are console-only.\n"
			+ "# Set true to also allow players with namelessiplogger.admin to run them.\n"
			+ "# Allowed values: true | false.\n"
			+ "commands.allow-admin-permission: " + quoteConfigValue(values.get("commands.allow-admin-permission")) + "\n"
			+ "\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# Update checks\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# Checks GitHub Releases for newer stable NamelessIPLogger versions and logs a notice.\n"
			+ "# Prereleases and the nightly tag are ignored.\n"
			+ "# This does not download or install updates.\n"
			+ "# Allowed values: true | false.\n"
			+ "updates.check-enabled: " + quoteConfigValue(values.get("updates.check-enabled")) + "\n"
			+ "# Interval in hours between update checks. Minimum effective value is 1.\n"
			+ "updates.check-interval-hours: " + quoteConfigValue(values.get("updates.check-interval-hours")) + "\n"
			+ "\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# Telemetry\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# Sends anonymous census pings to NamelessTelemetry:\n"
			+ "# https://github.com/NanashiTheNameless/NamelessTelemetry\n"
			+ "# Shared payload: SHA-256 hash of the instance ID, UTC date, project name, and count=1.\n"
			+ "# No player UUIDs, usernames, player IPs, connection logs, or GeoIP data are sent.\n"
			+ "# The local instance ID is stored separately in telemetry-instance-id.txt.\n"
			+ "# Allowed values: true | false.\n"
			+ "telemetry.enabled: " + quoteConfigValue(values.get("telemetry.enabled")) + "\n"
			+ unknownValues;
	}

	private static String quoteConfigValue(final String value) {
		if (value == null) {
			return "\"\"";
		}
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	public enum GeoIpProvider {
		DBIP,
		MAXMIND_GEOLITE2;

		public static GeoIpProvider from(final String rawValue) {
			if (rawValue == null) {
				return DBIP;
			}
			final String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
			if ("maxmind-geolite2".equals(normalized) || "maxmind".equals(normalized) || "geolite2".equals(normalized)) {
				return MAXMIND_GEOLITE2;
			}
			return DBIP;
		}
	}
}

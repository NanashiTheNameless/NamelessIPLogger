package dev.namelessnanashi.velocityiplogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
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
	boolean logConsoleDisconnect
) {
	private static final String DEFAULT_DBIP_URL = "https://download.db-ip.com/free/dbip-city-lite-YYYY-MM.mmdb.gz";
	private static final String DEFAULT_DBIP_ASN_URL = "https://download.db-ip.com/free/dbip-asn-lite-YYYY-MM.mmdb.gz";
	private static final String DEFAULT_MAXMIND_URL_TEMPLATE = "https://download.maxmind.com/geoip/databases/{edition_id}/download?suffix=tar.gz";
	private static final String DEFAULT_MAXMIND_EDITION = "GeoLite2-City";
	private static final int DEFAULT_REFRESH_DAYS = 7;

	public static PluginConfig load(final Path dataDirectory, final ComponentLogger logger) throws IOException {
		Files.createDirectories(dataDirectory);
		final Path configFile = dataDirectory.resolve("config.yml");
		if (Files.notExists(configFile)) {
			Files.writeString(configFile, defaultConfig(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
			logger.info("Created default config file at {}", configFile.toAbsolutePath().toString());
		}

		final Map<String, String> properties = parseSimpleYaml(configFile);

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
			logConsoleDisconnect
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
		final Map<String, String> values = new HashMap<>();
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

	private static String defaultConfig() {
		return "# ============================================================================\n"
			+ "# VelocityIPLogger - config.yml\n"
			+ "# ============================================================================\n"
			+ "# All keys are flat key:value pairs (no nested YAML objects required).\n"
			+ "#\n"
			+ "# Tips:\n"
			+ "# - Keep this file UTF-8 encoded.\n"
			+ "# - Apply edits with /viplookup reload (proxy restart is not required).\n"
			+ "# - Run /viplookup updatedb to force immediate GeoIP database redownload.\n"
			+ "# - Quote values to avoid YAML parser edge cases.\n"
			+ "#\n"
			+ "# Main provider for GeoIP database downloads/lookups.\n"
			+ "# Allowed values:\n"
			+ "#   - dbip\n"
			+ "#   - maxmind-geolite2\n"
			+ "#\n"
			+ "# Provider guidance:\n"
			+ "# - dbip: easiest setup, no license key required.\n"
			+ "# - maxmind-geolite2: requires a MaxMind key, generally better data quality.\n"
			+ "geoip.provider: \"dbip\"\n"
			+ "\n"
			+ "# Refresh interval in days for database redownload attempts.\n"
			+ "# Minimum effective value is 1.\n"
			+ "# Lower values update data sooner but increase network usage.\n"
			+ "geoip.refresh-days: \"7\"\n"
			+ "\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# DB-IP provider settings\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# YYYY-MM is expanded at runtime to the current year-month.\n"
			+ "# The configured URL remains the source of truth.\n"
			+ "# If download fails, plugin only tries .mmdb/.mmdb.gz extension fallback.\n"
			+ "# Compatibility: URLs using -latest also try current/recent monthly filenames.\n"
			+ "# DB-IP City MMDB gzip source (.mmdb.gz expected).\n"
			+ "geoip.dbip.url: \"https://download.db-ip.com/free/dbip-city-lite-YYYY-MM.mmdb.gz\"\n"
			+ "# DB-IP ASN MMDB gzip source (.mmdb.gz expected).\n"
			+ "geoip.dbip.asn-url: \"https://download.db-ip.com/free/dbip-asn-lite-YYYY-MM.mmdb.gz\"\n"
			+ "# If you host your own DB-IP mirror, set these to your mirror endpoints.\n"
			+ "\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# MaxMind GeoLite2 provider settings\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# License key from https://www.maxmind.com/\n"
			+ "# Required for maxmind-geolite2.\n"
			+ "# Account ID is optional but recommended for direct-download basic auth.\n"
			+ "geoip.maxmind.account-id: \"\"\n"
			+ "geoip.maxmind.license-key: \"\"\n"
			+ "# Keep the license key secret; do not share logs/screenshots with this value.\n"
			+ "# City edition id. Usually keep as GeoLite2-City.\n"
			+ "geoip.maxmind.edition-id: \"GeoLite2-City\"\n"
			+ "# ASN edition id. Usually keep as GeoLite2-ASN.\n"
			+ "geoip.maxmind.asn-edition-id: \"GeoLite2-ASN\"\n"
			+ "# Download URL template used for MaxMind edition downloads.\n"
			+ "# Placeholders:\n"
			+ "#   {edition_id}  -> replaced with edition id\n"
			+ "#   {license_key} -> replaced with license key (optional compatibility)\n"
			+ "# If account-id is set, basic auth is sent as account-id:license-key.\n"
			+ "geoip.maxmind.url-template: \"https://download.maxmind.com/geoip/databases/{edition_id}/download?suffix=tar.gz\"\n"
			+ "\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# Logging controls (data collection + persistence + console output)\n"
			+ "# --------------------------------------------------------------------------\n"
			+ "# Privacy note: username/IP/GeoIP toggles affect what is written to disk.\n"
			+ "# Warning: disabling log.include-ip can reduce correlation usefulness.\n"
			+ "# Include username in stored records/events.\n"
			+ "log.include-username: \"true\"\n"
			+ "# Include IP in stored records/events.\n"
			+ "log.include-ip: \"true\"\n"
			+ "# Perform and store GeoIP enrichment details.\n"
			+ "log.include-geoip: \"true\"\n"
			+ "# Write/update players.tsv\n"
			+ "log.write-players: \"true\"\n"
			+ "# Write/update ip_links.tsv\n"
			+ "log.write-ip-links: \"true\"\n"
			+ "# Append to connection_events.tsv\n"
			+ "log.write-connection-events: \"true\"\n"
			+ "# Maximum age (in days) to retain log rows based on event/last_seen time.\n"
			+ "# 0 disables retention pruning.\n"
			+ "log.max-retention-days: \"0\"\n"
			+ "# Print connect logs to proxy console\n"
			+ "log.console-connect: \"true\"\n"
			+ "# Print disconnect logs to proxy console\n"
			+ "log.console-disconnect: \"true\"\n";
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

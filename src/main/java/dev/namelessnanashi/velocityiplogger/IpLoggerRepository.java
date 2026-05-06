package dev.namelessnanashi.velocityiplogger;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public final class IpLoggerRepository {
	private static final String PLAYERS_HEADER = "uuid\tusername\tfirst_seen\tlast_seen\tlast_ip\tknown_ips\tknown_ips_count\n";
	private static final String IP_LINKS_HEADER = "uuid\tip\tfirst_seen\tlast_seen\ttimes_seen\tcountry_code\tcountry\tregion\tcity\ttimezone\tisp\tasn_number\tasn_org\tlat\tlon\tgeo_status\tgeo_message\tgeo_summary\n";
	private static final String CONNECTION_EVENTS_HEADER = "event_time\tevent_type\tsession_id\tuuid\tusername\tip\tduration_seconds\treason\tduration_human\n";

	private final Path dataDirectory;
	private final ComponentLogger logger;
	private volatile PluginConfig config;
	private final ReentrantLock lock;

	private final Map<UUID, PlayerRecord> players;
	private final Map<String, IpLinkRecord> ipLinks;
	private final Map<UUID, SessionStart> activeSessions;
	private final AtomicLong nextSessionId;

	private Path playersFile;
	private Path ipLinksFile;
	private Path connectionEventsFile;

	public IpLoggerRepository(final Path dataDirectory, final ComponentLogger logger, final PluginConfig config) {
		this.dataDirectory = dataDirectory;
		this.logger = logger;
		this.config = config;
		this.lock = new ReentrantLock();
		this.players = new ConcurrentHashMap<>();
		this.ipLinks = new ConcurrentHashMap<>();
		this.activeSessions = new ConcurrentHashMap<>();
		this.nextSessionId = new AtomicLong(1);
	}

	public void setConfig(final PluginConfig config) {
		if (config != null) {
			this.config = config;
		}
	}

	public void initialize() throws IOException {
		Files.createDirectories(dataDirectory);
		playersFile = dataDirectory.resolve("players.tsv");
		ipLinksFile = dataDirectory.resolve("ip_links.tsv");
		connectionEventsFile = dataDirectory.resolve("connection_events.tsv");

		if (Files.notExists(playersFile)) {
			Files.writeString(playersFile, PLAYERS_HEADER, StandardCharsets.UTF_8);
		}
		if (Files.notExists(ipLinksFile)) {
			Files.writeString(ipLinksFile, IP_LINKS_HEADER, StandardCharsets.UTF_8);
		}
		if (Files.notExists(connectionEventsFile)) {
			Files.writeString(connectionEventsFile, CONNECTION_EVENTS_HEADER, StandardCharsets.UTF_8);
		}

		loadPlayers();
		loadIpLinks();
		pruneRetention(Instant.now());
		loadNextSessionId();
	}

	public void recordConnect(
		final UUID uuid,
		final String username,
		final String ip,
		final Instant now,
		final GeoIpInfo geoIpInfo
	) throws IOException {
		lock.lock();
		try {
			pruneRetention(now);

			players.compute(uuid, (ignored, existing) -> {
				if (existing == null) {
					return new PlayerRecord(uuid, username, now, now, ip, safeIpList(ip));
				}
				return existing.withUpdate(username, now, ip);
			});

			final String key = uuid + "|" + ip;
			ipLinks.compute(key, (ignored, existing) -> {
				if (existing == null) {
					return IpLinkRecord.firstSeen(uuid, ip, now, geoIpInfo);
				}
				return existing.bump(now, geoIpInfo);
			});

			final long sessionId = nextSessionId.getAndIncrement();
			activeSessions.put(uuid, new SessionStart(sessionId, now));
			if (config.logWriteConnectionEvents()) {
				appendConnectionEvent(now, "CONNECT", sessionId, uuid, username, ip, "", "");
			}

			if (config.logWritePlayers()) {
				persistPlayers();
			}
			if (config.logWriteIpLinks()) {
				persistIpLinks();
			}
		} finally {
			lock.unlock();
		}
	}

	public void recordDisconnect(
		final UUID uuid,
		final String username,
		final String ip,
		final Instant now,
		final String reason
	) throws IOException {
		lock.lock();
		try {
			pruneRetention(now);

			final SessionStart sessionStart = activeSessions.remove(uuid);
			final String durationSeconds;
			final long sessionId;
			if (sessionStart != null) {
				sessionId = sessionStart.sessionId();
				durationSeconds = Long.toString(Math.max(0, Duration.between(sessionStart.connectedAt(), now).getSeconds()));
			} else {
				sessionId = -1L;
				durationSeconds = "";
			}

			if (config.logWriteConnectionEvents()) {
				appendConnectionEvent(now, "DISCONNECT", sessionId, uuid, username, ip, durationSeconds, reason == null ? "" : reason);
			}
		} finally {
			lock.unlock();
		}
	}

	public Optional<PlayerInfoView> findByUuid(final UUID uuid) {
		lock.lock();
		try {
			final PlayerRecord record = players.get(uuid);
			if (record == null) {
				return Optional.empty();
			}

			final List<IpLinkView> linkedIps = new ArrayList<>();
			for (final IpLinkRecord ipLinkRecord : ipLinks.values()) {
				if (ipLinkRecord.uuid().equals(uuid)) {
					linkedIps.add(toView(ipLinkRecord));
				}
			}

			linkedIps.sort(Comparator.comparing(IpLinkView::lastSeen).reversed());
			return Optional.of(new PlayerInfoView(record.uuid(), record.username(), record.firstSeen(), record.lastSeen(), record.lastIp(), linkedIps));
		} finally {
			lock.unlock();
		}
	}

	public List<PlayerInfoView> findByUsername(final String username) {
		final String normalized = username == null ? "" : username.toLowerCase(Locale.ROOT);
		lock.lock();
		try {
			final List<PlayerInfoView> results = new ArrayList<>();
			for (final PlayerRecord playerRecord : players.values()) {
				if (!playerRecord.username().toLowerCase(Locale.ROOT).equals(normalized)) {
					continue;
				}
				final List<IpLinkView> linkedIps = new ArrayList<>();
				for (final IpLinkRecord ipLinkRecord : ipLinks.values()) {
					if (ipLinkRecord.uuid().equals(playerRecord.uuid())) {
						linkedIps.add(toView(ipLinkRecord));
					}
				}
				linkedIps.sort(Comparator.comparing(IpLinkView::lastSeen).reversed());
				results.add(new PlayerInfoView(playerRecord.uuid(), playerRecord.username(), playerRecord.firstSeen(), playerRecord.lastSeen(), playerRecord.lastIp(), linkedIps));
			}

			results.sort(Comparator.comparing(PlayerInfoView::lastSeen).reversed());
			return results;
		} finally {
			lock.unlock();
		}
	}

	public List<IpCorrelationView> findByIp(final String ip) {
		lock.lock();
		try {
			final List<IpCorrelationView> results = new ArrayList<>();
			for (final IpLinkRecord ipLinkRecord : ipLinks.values()) {
				if (!ipLinkRecord.ip().equals(ip)) {
					continue;
				}

				final PlayerRecord playerRecord = players.get(ipLinkRecord.uuid());
				final String username = playerRecord == null ? "unknown" : playerRecord.username();
				results.add(new IpCorrelationView(
					ipLinkRecord.ip(),
					ipLinkRecord.uuid(),
					username,
					ipLinkRecord.firstSeen(),
					ipLinkRecord.lastSeen(),
					ipLinkRecord.timesSeen(),
					ipLinkRecord.countryCode(),
					ipLinkRecord.country(),
					ipLinkRecord.region(),
					ipLinkRecord.city(),
					ipLinkRecord.timezone(),
					ipLinkRecord.asnNumber(),
					ipLinkRecord.asnOrganization(),
					ipLinkRecord.latitude(),
					ipLinkRecord.longitude(),
					ipLinkRecord.geoStatus(),
					ipLinkRecord.geoMessage()
				));
			}

			results.sort(Comparator.comparing(IpCorrelationView::lastSeen).reversed());
			return results;
		} finally {
			lock.unlock();
		}
	}

	private static IpLinkView toView(final IpLinkRecord record) {
		return new IpLinkView(
			record.ip(),
			record.firstSeen(),
			record.lastSeen(),
			record.timesSeen(),
			record.countryCode(),
			record.country(),
			record.region(),
			record.city(),
			record.timezone(),
			record.asnNumber(),
			record.asnOrganization(),
			record.latitude(),
			record.longitude(),
			record.geoStatus(),
			record.geoMessage()
		);
	}

	private void loadPlayers() throws IOException {
		for (final String line : Files.readAllLines(playersFile, StandardCharsets.UTF_8)) {
			if (line.startsWith("uuid\t") || line.isBlank()) {
				continue;
			}
			final String[] cols = line.split("\\t", -1);
			if (cols.length < 5) {
				continue;
			}
			try {
				final UUID uuid = UUID.fromString(cols[0]);
				final String knownIps = cols.length >= 6 ? cols[5] : safeIpList(cols[4]);
				final PlayerRecord record = new PlayerRecord(
					uuid,
					cols[1],
					Instant.parse(cols[2]),
					Instant.parse(cols[3]),
					cols[4],
					knownIps
				);
				players.put(uuid, record);
			} catch (final Exception exception) {
				logger.warn("Failed to parse player row: {}", line);
			}
		}
	}

	private void loadIpLinks() throws IOException {
		for (final String line : Files.readAllLines(ipLinksFile, StandardCharsets.UTF_8)) {
			if (line.startsWith("uuid\t") || line.isBlank()) {
				continue;
			}
			final String[] cols = line.split("\\t", -1);
			if (cols.length < 15) {
				continue;
			}
			try {
				final UUID uuid = UUID.fromString(cols[0]);
				final IpLinkRecord record;
				if (cols.length >= 17) {
					record = new IpLinkRecord(
						uuid,
						cols[1],
						Instant.parse(cols[2]),
						Instant.parse(cols[3]),
						Long.parseLong(cols[4]),
						cols[5],
						cols[6],
						cols[7],
						cols[8],
						cols[9],
						cols[10],
						cols[11],
						cols[12],
						cols[13],
						cols[14],
						cols[15],
						cols[16]
					);
				} else {
					record = new IpLinkRecord(
						uuid,
						cols[1],
						Instant.parse(cols[2]),
						Instant.parse(cols[3]),
						Long.parseLong(cols[4]),
						cols[5],
						cols[6],
						cols[7],
						cols[8],
						cols[9],
						cols[10],
						"",
						"",
						cols[11],
						cols[12],
						cols[13],
						cols[14]
					);
				}
				ipLinks.put(key(uuid, cols[1]), record);
			} catch (final Exception exception) {
				logger.warn("Failed to parse ip link row: {}", line);
			}
		}
	}

	private void loadNextSessionId() throws IOException {
		long maxId = 0;
		for (final String line : Files.readAllLines(connectionEventsFile, StandardCharsets.UTF_8)) {
			if (line.startsWith("event_time\t") || line.isBlank()) {
				continue;
			}
			final String[] cols = line.split("\\t", -1);
			if (cols.length < 3 || cols[2].isBlank()) {
				continue;
			}
			try {
				final long id = Long.parseLong(cols[2]);
				if (id > maxId) {
					maxId = id;
				}
			} catch (final NumberFormatException ignored) {
			}
		}
		nextSessionId.set(maxId + 1);
	}

	private void persistPlayers() throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(playersFile, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
			writer.write(PLAYERS_HEADER);
			players.values().stream()
				.sorted(Comparator.comparing(PlayerRecord::lastSeen).reversed())
				.forEach(record -> {
					try {
						writer.write(
							record.uuid() + "\t"
								+ sanitize(record.username()) + "\t"
								+ record.firstSeen() + "\t"
								+ record.lastSeen() + "\t"
								+ sanitize(record.lastIp()) + "\t"
								+ sanitize(record.knownIps()) + "\t"
								+ countKnownIps(record.knownIps())
								+ "\n"
						);
					} catch (final IOException ignored) {
					}
				});
		}
	}

	private void persistIpLinks() throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(ipLinksFile, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
			writer.write(IP_LINKS_HEADER);
			ipLinks.values().stream()
				.sorted(Comparator.comparing(IpLinkRecord::lastSeen).reversed())
				.forEach(record -> {
					try {
						writer.write(
							record.uuid() + "\t"
								+ sanitize(record.ip()) + "\t"
								+ record.firstSeen() + "\t"
								+ record.lastSeen() + "\t"
								+ record.timesSeen() + "\t"
								+ sanitize(record.countryCode()) + "\t"
								+ sanitize(record.country()) + "\t"
								+ sanitize(record.region()) + "\t"
								+ sanitize(record.city()) + "\t"
								+ sanitize(record.timezone()) + "\t"
								+ sanitize(record.isp()) + "\t"
								+ sanitize(record.asnNumber()) + "\t"
								+ sanitize(record.asnOrganization()) + "\t"
								+ sanitize(record.latitude()) + "\t"
								+ sanitize(record.longitude()) + "\t"
								+ sanitize(record.geoStatus()) + "\t"
								+ sanitize(record.geoMessage()) + "\t"
								+ sanitize(geoSummary(record))
								+ "\n"
						);
					} catch (final IOException ignored) {
					}
				});
		}
	}

	private void appendConnectionEvent(
		final Instant now,
		final String eventType,
		final long sessionId,
		final UUID uuid,
		final String username,
		final String ip,
		final String durationSeconds,
		final String reason
	) throws IOException {
		final String humanDuration = humanDuration(durationSeconds);
		Files.writeString(
			connectionEventsFile,
			now + "\t" + eventType + "\t" + sessionId + "\t" + uuid + "\t" + sanitize(username) + "\t" + sanitize(ip)
				+ "\t" + sanitize(durationSeconds) + "\t" + sanitize(reason) + "\t" + sanitize(humanDuration) + "\n",
			StandardCharsets.UTF_8,
			StandardOpenOption.APPEND
		);
	}

	private void pruneRetention(final Instant now) throws IOException {
		final Instant cutoff = retentionCutoff(now);
		if (cutoff == null) {
			return;
		}

		players.entrySet().removeIf(entry -> entry.getValue().lastSeen().isBefore(cutoff));
		ipLinks.entrySet().removeIf(entry -> {
			final IpLinkRecord record = entry.getValue();
			return record.lastSeen().isBefore(cutoff) || !players.containsKey(record.uuid());
		});

		pruneConnectionEventsFile(cutoff);
	}

	private Instant retentionCutoff(final Instant now) {
		final int days = config.logMaxRetentionDays();
		if (days <= 0) {
			return null;
		}
		return now.minus(Duration.ofDays(days));
	}

	private void pruneConnectionEventsFile(final Instant cutoff) throws IOException {
		if (cutoff == null || Files.notExists(connectionEventsFile)) {
			return;
		}

		final List<String> lines = Files.readAllLines(connectionEventsFile, StandardCharsets.UTF_8);
		final List<String> kept = new ArrayList<>();
		kept.add(CONNECTION_EVENTS_HEADER.stripTrailing());

		for (final String line : lines) {
			if (line.startsWith("event_time\t") || line.isBlank()) {
				continue;
			}

			final String[] cols = line.split("\\t", -1);
			if (cols.length < 1) {
				continue;
			}

			try {
				final Instant eventTime = Instant.parse(cols[0]);
				if (!eventTime.isBefore(cutoff)) {
					kept.add(line);
				}
			} catch (final Exception exception) {
				kept.add(line);
			}
		}

		Files.write(connectionEventsFile, kept, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
	}

	private static String sanitize(final String value) {
		if (value == null) {
			return "";
		}
		return value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
	}

	private static String safeIpList(final String ip) {
		if (ip == null || ip.isBlank()) {
			return "";
		}
		return ip;
	}

	private static String mergeKnownIps(final String existingKnownIps, final String newIp) {
		if (newIp == null || newIp.isBlank()) {
			return existingKnownIps == null ? "" : existingKnownIps;
		}

		final List<String> ips = new ArrayList<>();
		if (existingKnownIps != null && !existingKnownIps.isBlank()) {
			for (final String part : existingKnownIps.split(",")) {
				final String trimmed = part.trim();
				if (!trimmed.isBlank() && !ips.contains(trimmed)) {
					ips.add(trimmed);
				}
			}
		}

		if (!ips.contains(newIp)) {
			ips.add(newIp);
		}

		return String.join(",", ips);
	}

	private static int countKnownIps(final String knownIps) {
		if (knownIps == null || knownIps.isBlank()) {
			return 0;
		}
		int count = 0;
		for (final String part : knownIps.split(",")) {
			if (!part.trim().isBlank()) {
				count++;
			}
		}
		return count;
	}

	private static String geoSummary(final IpLinkRecord record) {
		final List<String> parts = new ArrayList<>();
		if (!record.country().isBlank()) {
			parts.add(record.country());
		}
		if (!record.region().isBlank()) {
			parts.add(record.region());
		}
		if (!record.city().isBlank()) {
			parts.add(record.city());
		}
		if (!record.asnNumber().isBlank()) {
			parts.add(record.asnNumber());
		}
		if (!record.asnOrganization().isBlank()) {
			parts.add(record.asnOrganization());
		}
		return String.join(" | ", parts);
	}

	private static String humanDuration(final String durationSeconds) {
		if (durationSeconds == null || durationSeconds.isBlank()) {
			return "";
		}
		try {
			final long seconds = Long.parseLong(durationSeconds);
			final long hours = seconds / 3600;
			final long minutes = (seconds % 3600) / 60;
			final long remSeconds = seconds % 60;
			return hours + "h " + minutes + "m " + remSeconds + "s";
		} catch (final NumberFormatException exception) {
			return "";
		}
	}

	private static String key(final UUID uuid, final String ip) {
		return uuid + "|" + ip;
	}

	private record SessionStart(long sessionId, Instant connectedAt) {
	}

	public record PlayerInfoView(
		UUID uuid,
		String username,
		Instant firstSeen,
		Instant lastSeen,
		String lastIp,
		List<IpLinkView> ipLinks
	) {
	}

	public record IpLinkView(
		String ip,
		Instant firstSeen,
		Instant lastSeen,
		long timesSeen,
		String countryCode,
		String country,
		String region,
		String city,
		String timezone,
		String asnNumber,
		String asnOrganization,
		String latitude,
		String longitude,
		String geoStatus,
		String geoMessage
	) {
	}

	public record IpCorrelationView(
		String ip,
		UUID uuid,
		String username,
		Instant firstSeen,
		Instant lastSeen,
		long timesSeen,
		String countryCode,
		String country,
		String region,
		String city,
		String timezone,
		String asnNumber,
		String asnOrganization,
		String latitude,
		String longitude,
		String geoStatus,
		String geoMessage
	) {
	}

	private record PlayerRecord(
		UUID uuid,
		String username,
		Instant firstSeen,
		Instant lastSeen,
		String lastIp,
		String knownIps
	) {
		private PlayerRecord withUpdate(final String newUsername, final Instant newLastSeen, final String newLastIp) {
			return new PlayerRecord(
				uuid,
				newUsername,
				firstSeen,
				newLastSeen,
				newLastIp,
				mergeKnownIps(knownIps, newLastIp)
			);
		}
	}

	private record IpLinkRecord(
		UUID uuid,
		String ip,
		Instant firstSeen,
		Instant lastSeen,
		long timesSeen,
		String countryCode,
		String country,
		String region,
		String city,
		String timezone,
		String isp,
		String asnNumber,
		String asnOrganization,
		String latitude,
		String longitude,
		String geoStatus,
		String geoMessage
	) {
		private static IpLinkRecord firstSeen(final UUID uuid, final String ip, final Instant now, final GeoIpInfo geoIpInfo) {
			return new IpLinkRecord(
				uuid,
				ip,
				now,
				now,
				1,
				safe(geoIpInfo.countryCode()),
				safe(geoIpInfo.country()),
				safe(geoIpInfo.region()),
				safe(geoIpInfo.city()),
				safe(geoIpInfo.timezone()),
				safe(geoIpInfo.isp()),
				safe(geoIpInfo.asnNumber()),
				safe(geoIpInfo.asnOrganization()),
				safe(geoIpInfo.latitude()),
				safe(geoIpInfo.longitude()),
				safe(geoIpInfo.status()),
				safe(geoIpInfo.message())
			);
		}

		private IpLinkRecord bump(final Instant now, final GeoIpInfo geoIpInfo) {
			return new IpLinkRecord(
				uuid,
				ip,
				firstSeen,
				now,
				timesSeen + 1,
				nonBlankOrDefault(geoIpInfo.countryCode(), countryCode),
				nonBlankOrDefault(geoIpInfo.country(), country),
				nonBlankOrDefault(geoIpInfo.region(), region),
				nonBlankOrDefault(geoIpInfo.city(), city),
				nonBlankOrDefault(geoIpInfo.timezone(), timezone),
				nonBlankOrDefault(geoIpInfo.isp(), isp),
				nonBlankOrDefault(geoIpInfo.asnNumber(), asnNumber),
				nonBlankOrDefault(geoIpInfo.asnOrganization(), asnOrganization),
				nonBlankOrDefault(geoIpInfo.latitude(), latitude),
				nonBlankOrDefault(geoIpInfo.longitude(), longitude),
				nonBlankOrDefault(geoIpInfo.status(), geoStatus),
				nonBlankOrDefault(geoIpInfo.message(), geoMessage)
			);
		}

		private static String nonBlankOrDefault(final String value, final String fallback) {
			if (value == null || value.isBlank()) {
				return fallback;
			}
			return value;
		}

		private static String safe(final String value) {
			return value == null ? "" : value;
		}
	}
}

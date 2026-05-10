package dev.namelessnanashi.velocityiplogger;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.URI;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

final class TelemetryService {
	private static final URI ENDPOINT = URI.create("https://telemetry.namelessnanashi.dev/census");
	private static final String PROJECT_NAME = "VelocityIPLogger";
	private static final String INSTANCE_ID_FILE_NAME = "telemetry-instance-id.txt";
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
	private static final long PERIOD_HOURS = 2;

	private final ComponentLogger logger;
	private final Path dataDirectory;
	private final PluginConfig config;
	private final HttpClient httpClient;
	private ScheduledExecutorService scheduler;

	TelemetryService(final ComponentLogger logger, final Path dataDirectory, final PluginConfig config) {
		this.logger = logger;
		this.dataDirectory = dataDirectory;
		this.config = config;
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(REQUEST_TIMEOUT)
			.build();
	}

	void start() {
		if (!config.telemetryEnabled()) {
			logger.info("Telemetry disabled by config.");
			return;
		}
		if (!"https".equalsIgnoreCase(ENDPOINT.getScheme())) {
			logger.warn("Telemetry disabled because endpoint is not HTTPS: {}", ENDPOINT);
			return;
		}

		try {
			ensureInstanceId();
		} catch (final Exception exception) {
			logger.debug("Telemetry instance ID initialization failed: {}", exception.getMessage());
		}

		scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			final Thread thread = new Thread(runnable, "VelocityIPLogger-Telemetry");
			thread.setDaemon(true);
			return thread;
		});

		scheduler.execute(this::sendSafely);
		scheduler.scheduleAtFixedRate(
			this::sendSafely,
			secondsUntilNextEvenUtcHour(),
			TimeUnit.HOURS.toSeconds(PERIOD_HOURS),
			TimeUnit.SECONDS
		);
	}

	void shutdown() {
		if (scheduler != null) {
			scheduler.shutdownNow();
			scheduler = null;
		}
	}

	private void sendSafely() {
		try {
			if (!isEndpointHostAllowed()) {
				logger.warn("Telemetry send skipped because endpoint resolved to a private, link-local, loopback, or otherwise unsafe address: {}", ENDPOINT);
				return;
			}
			final String instanceId = ensureInstanceId();
			final String payload = payloadFor(instanceId);
			final HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.header("User-Agent", PROJECT_NAME + "/" + Constants.VERSION + " telemetry")
				.header("X-Project-Name", PROJECT_NAME)
				.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
				.build();

			httpClient.send(request, HttpResponse.BodyHandlers.discarding());
		} catch (final InterruptedException exception) {
			Thread.currentThread().interrupt();
		} catch (final Exception exception) {
			logger.debug("Telemetry send failed: {}", exception.getMessage());
		}
	}

	private static boolean isEndpointHostAllowed() throws IOException {
		final String host = ENDPOINT.getHost();
		if (host == null || host.isBlank()) {
			return false;
		}

		for (final InetAddress address : InetAddress.getAllByName(host)) {
			if (!isAddressAllowed(address)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isAddressAllowed(final InetAddress address) {
		if (address.isAnyLocalAddress()
			|| address.isLoopbackAddress()
			|| address.isLinkLocalAddress()
			|| address.isSiteLocalAddress()
			|| address.isMulticastAddress()) {
			return false;
		}

		if (address instanceof Inet4Address) {
			return true;
		}
		if (address instanceof Inet6Address inet6Address) {
			return !isIpv6UniqueLocal(inet6Address);
		}
		return false;
	}

	private static boolean isIpv6UniqueLocal(final Inet6Address address) {
		final byte firstByte = address.getAddress()[0];
		return (firstByte & 0xfe) == 0xfc;
	}

	private String ensureInstanceId() throws IOException {
		Files.createDirectories(dataDirectory);
		final Path instanceIdFile = dataDirectory.resolve(INSTANCE_ID_FILE_NAME);
		if (Files.exists(instanceIdFile)) {
			final String existing = Files.readString(instanceIdFile, StandardCharsets.UTF_8).trim();
			if (!existing.isBlank()) {
				return existing;
			}
		}

		final String created = UUID.randomUUID().toString();
		Files.writeString(
			instanceIdFile,
			created + System.lineSeparator(),
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE
		);
		return created;
	}

	private static String payloadFor(final String instanceId) {
		final String date = DateTimeFormatter.ISO_LOCAL_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));
		final String hashedId = sha256Hex(instanceId);
		return "{"
			+ "\"id\":\"" + hashedId + "\","
			+ "\"date\":\"" + date + "\","
			+ "\"projectname\":\"" + PROJECT_NAME + "\","
			+ "\"project\":\"" + PROJECT_NAME + "\","
			+ "\"count\":1"
			+ "}";
	}

	private static String sha256Hex(final String value) {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (final NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private static long secondsUntilNextEvenUtcHour() {
		final ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
		final ZonedDateTime base = now.withMinute(0).withSecond(0).withNano(0);
		final ZonedDateTime next = base.getHour() % PERIOD_HOURS == 0
			? base.plusHours(PERIOD_HOURS)
			: base.plusHours(1);
		return Math.max(1, Duration.between(now, next).getSeconds());
	}
}

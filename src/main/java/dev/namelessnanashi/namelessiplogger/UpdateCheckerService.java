package dev.namelessnanashi.namelessiplogger;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

final class UpdateCheckerService {
	private static final URI RELEASES_ENDPOINT = URI
			.create("https://api.github.com/repos/NanashiTheNameless/NamelessIPLogger/releases");
	private static final String IGNORED_TAG = "nightly";
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

	private final ComponentLogger logger;
	private final ProxyServer proxyServer;
	private final PluginConfig config;
	private final PluginStrings strings;
	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private volatile Release latestAvailableRelease;
	private ScheduledExecutorService scheduler;

	UpdateCheckerService(final ComponentLogger logger, final ProxyServer proxyServer, final PluginConfig config,
			final PluginStrings strings) {
		this.logger = logger;
		this.proxyServer = proxyServer;
		this.config = config;
		this.strings = strings;
		this.httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
		this.objectMapper = new ObjectMapper();
	}

	void start() {
		if (!config.updateCheckEnabled()) {
			logger.info("Update checks disabled by config.");
			return;
		}
		if (!"https".equalsIgnoreCase(RELEASES_ENDPOINT.getScheme())) {
			logger.warn("Update checks disabled because endpoint is not HTTPS: {}", RELEASES_ENDPOINT);
			return;
		}

		scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
			final Thread thread = new Thread(runnable, "NamelessIPLogger-UpdateChecker");
			thread.setDaemon(true);
			return thread;
		});
		scheduler.execute(this::checkSafely);
		scheduler.scheduleAtFixedRate(this::checkSafely, config.updateCheckIntervalHours(),
				config.updateCheckIntervalHours(), TimeUnit.HOURS);
	}

	void shutdown() {
		if (scheduler != null) {
			scheduler.shutdownNow();
			scheduler = null;
		}
	}

	private void checkSafely() {
		final UpdateCheckResult result = performCheck(true);
		if (!result.success()) {
			logger.debug(result.message());
		}
	}

	UpdateCheckResult checkNow() {
		return performCheck(false);
	}

	private UpdateCheckResult performCheck(final boolean notifyAdmins) {
		try {
			if (!isEndpointHostAllowed()) {
				final String message = strings.format("updates.endpoint-blocked", "endpoint",
						RELEASES_ENDPOINT.toString());
				logger.warn(message);
				return new UpdateCheckResult(false, false, message);
			}

			final HttpRequest request = HttpRequest.newBuilder(RELEASES_ENDPOINT).timeout(REQUEST_TIMEOUT)
					.header("Accept", "application/vnd.github+json")
					.header("User-Agent", "NamelessIPLogger/" + Constants.VERSION + " update-checker").GET().build();
			final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				return new UpdateCheckResult(false, false,
						strings.format("updates.http-failure", "status", Integer.toString(response.statusCode())));
			}

			final Release latestRelease = latestRelease(response.body());
			if (latestRelease == null) {
				latestAvailableRelease = null;
				return new UpdateCheckResult(true, false, strings.get("updates.no-release"));
			}

			if (compareVersions(latestRelease.version(), Constants.VERSION) > 0) {
				latestAvailableRelease = latestRelease;
				logger.info("NamelessIPLogger update available: current={} latest={} ({})", Constants.VERSION,
						latestRelease.tagName(), latestRelease.url());
				if (notifyAdmins) {
					notifyOnlineAdmins(latestRelease);
				}
				return new UpdateCheckResult(true, true, strings.format("updates.available", "current",
						Constants.VERSION, "latest", latestRelease.tagName(), "url", latestRelease.url()));
			}

			latestAvailableRelease = null;
			return new UpdateCheckResult(true, false, strings.format("updates.current", "current", Constants.VERSION));
		} catch (final InterruptedException exception) {
			Thread.currentThread().interrupt();
			return new UpdateCheckResult(false, false, strings.get("updates.interrupted"));
		} catch (final Exception exception) {
			return new UpdateCheckResult(false, false, strings.format("updates.failure", "error",
					exception.getMessage() == null ? "unknown error" : exception.getMessage()));
		}
	}

	void notifyPlayerIfOutdated(final Player player) {
		final Release update = latestAvailableRelease;
		if (update != null && shouldNotify(player)) {
			player.sendMessage(updateMessage(update));
		}
	}

	private void notifyOnlineAdmins(final Release update) {
		final Component message = updateMessage(update);
		for (final Player player : proxyServer.getAllPlayers()) {
			if (shouldNotify(player)) {
				player.sendMessage(message);
			}
		}
	}

	private static boolean shouldNotify(final Player player) {
		return player.hasPermission(PluginPermissions.UPDATE_NOTIFY) || player.hasPermission(PluginPermissions.ADMIN);
	}

	private Component updateMessage(final Release update) {
		return Component.text(strings.get("prefix") + " " + strings.format("updates.available", "current",
				Constants.VERSION, "latest", update.tagName(), "url", update.url()));
	}

	private Release latestRelease(final String responseBody) throws IOException {
		final JsonNode releases = objectMapper.readTree(responseBody);
		if (!releases.isArray()) {
			return null;
		}

		Release latest = null;
		for (final JsonNode release : releases) {
			if (release.path("draft").asBoolean(false)) {
				continue;
			}
			if (release.path("prerelease").asBoolean(false)) {
				continue;
			}

			final String tagName = release.path("tag_name").asText("");
			if (tagName.isBlank() || IGNORED_TAG.equalsIgnoreCase(tagName.trim())) {
				continue;
			}

			final Release candidate = new Release(tagName, normalizeVersion(tagName),
					release.path("html_url").asText("https://github.com/NanashiTheNameless/NamelessIPLogger/releases"));
			if (latest == null || compareVersions(candidate.version(), latest.version()) > 0) {
				latest = candidate;
			}
		}
		return latest;
	}

	private static int compareVersions(final String left, final String right) {
		final List<String> leftParts = versionParts(left);
		final List<String> rightParts = versionParts(right);
		final int maxSize = Math.max(leftParts.size(), rightParts.size());
		for (int index = 0; index < maxSize; index++) {
			final String leftPart = index < leftParts.size() ? leftParts.get(index) : "0";
			final String rightPart = index < rightParts.size() ? rightParts.get(index) : "0";
			final int compared = compareVersionPart(leftPart, rightPart);
			if (compared != 0) {
				return compared;
			}
		}
		return 0;
	}

	private static int compareVersionPart(final String left, final String right) {
		final boolean leftNumeric = isInteger(left);
		final boolean rightNumeric = isInteger(right);
		if (leftNumeric && rightNumeric) {
			return compareNumericParts(left, right);
		}
		if (leftNumeric) {
			return 1;
		}
		if (rightNumeric) {
			return -1;
		}
		return left.compareTo(right);
	}

	private static List<String> versionParts(final String version) {
		final String normalized = normalizeVersion(version);
		final String[] rawParts = normalized.split("[.-]");
		final List<String> parts = new ArrayList<>();
		for (final String rawPart : rawParts) {
			if (!rawPart.isBlank()) {
				parts.add(rawPart);
			}
		}
		return parts;
	}

	private static String normalizeVersion(final String version) {
		final String normalized = version.trim().toLowerCase(Locale.ROOT);
		return normalized.startsWith("v") ? normalized.substring(1) : normalized;
	}

	private static boolean isInteger(final String value) {
		if (value.isBlank()) {
			return false;
		}
		for (int index = 0; index < value.length(); index++) {
			if (!Character.isDigit(value.charAt(index))) {
				return false;
			}
		}
		return true;
	}

	private static int compareNumericParts(final String left, final String right) {
		final String normalizedLeft = stripLeadingZeroes(left);
		final String normalizedRight = stripLeadingZeroes(right);
		final int lengthCompare = Integer.compare(normalizedLeft.length(), normalizedRight.length());
		if (lengthCompare != 0) {
			return lengthCompare;
		}
		return normalizedLeft.compareTo(normalizedRight);
	}

	private static String stripLeadingZeroes(final String value) {
		int index = 0;
		while (index < value.length() - 1 && value.charAt(index) == '0') {
			index++;
		}
		return value.substring(index);
	}

	private static boolean isEndpointHostAllowed() throws IOException {
		final String host = RELEASES_ENDPOINT.getHost();
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
		if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
				|| address.isSiteLocalAddress() || address.isMulticastAddress()) {
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

	private record Release(String tagName, String version, String url) {
	}

	record UpdateCheckResult(boolean success, boolean updateAvailable, String message) {
	}
}

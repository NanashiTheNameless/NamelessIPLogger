package dev.namelessnanashi.namelessiplogger;

import com.maxmind.db.CHMCache;
import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.CityResponse;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GeoIpService {
	private static final Duration CACHE_TTL = Duration.ofHours(24);
	private static final int CONNECT_TIMEOUT_MS = 15_000;
	private static final int READ_TIMEOUT_MS = 60_000;
	private static final DateTimeFormatter YEAR_MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

	private final ComponentLogger logger;
	private final Path cityDatabaseFile;
	private final Path asnDatabaseFile;
	private final PluginConfig config;
	private final Duration dbRefreshInterval;
	private final Map<String, CachedGeoIp> cache;
	private DatabaseReader cityReader;
	private DatabaseReader asnReader;
	private String lastInitializationError;

	public GeoIpService(final ComponentLogger logger, final Path dataDirectory, final PluginConfig config) {
		this.logger = logger;
		this.config = config;
		this.cityDatabaseFile = dataDirectory.resolve("geoip").resolve(cityDatabaseFileName(config.geoIpProvider()));
		this.asnDatabaseFile = dataDirectory.resolve("geoip").resolve(asnDatabaseFileName(config.geoIpProvider()));
		this.dbRefreshInterval = Duration.ofDays(config.geoIpRefreshDays());
		this.cache = new ConcurrentHashMap<>();
	}

	public synchronized void initialize() {
		try {
			Files.createDirectories(cityDatabaseFile.getParent());
			ensureDatabasePresent();
			reopenReaders();
			lastInitializationError = null;
		} catch (final Exception exception) {
			logger.error("Failed to initialize GeoIP database", exception);
			lastInitializationError = exception.getMessage() == null ? "unknown error" : exception.getMessage();
			closeReaders();
		}
	}

	public synchronized void shutdown() {
		closeReaders();
	}

	public synchronized void refreshNow() throws IOException {
		Files.createDirectories(cityDatabaseFile.getParent());
		downloadDatabase();
		reopenReaders();
		lastInitializationError = null;
		cache.clear();
		logger.info("GeoIP databases refreshed immediately");
	}

	public synchronized boolean isReady() {
		return cityReader != null;
	}

	public synchronized String lastInitializationError() {
		return lastInitializationError == null ? "GeoIP initialization failed" : lastInitializationError;
	}

	public GeoIpInfo lookup(final String ip) {
		if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
			return GeoIpInfo.unavailable();
		}

		if (isPrivateAddress(ip)) {
			return new GeoIpInfo("private", "", "", "", "", "", "", "", "", "", "", "private-address");
		}

		final CachedGeoIp cached = cache.get(ip);
		if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
			return cached.info();
		}

		final GeoIpInfo resolved = fetchFromDatabase(ip);
		cache.put(ip, new CachedGeoIp(resolved, Instant.now().plus(CACHE_TTL)));
		return resolved;
	}

	private GeoIpInfo fetchFromDatabase(final String ip) {
		if (cityReader == null) {
			return GeoIpInfo.unavailable();
		}

		try {
			final InetAddress address = InetAddress.getByName(ip);
			final CityResponse response = cityReader.city(address);
			final String country = response.getCountry() == null ? "" : safe(response.getCountry().getName());
			final String countryCode = response.getCountry() == null ? "" : safe(response.getCountry().getIsoCode());
			final String city = response.getCity() == null ? "" : safe(response.getCity().getName());
			final String timezone = response.getLocation() == null ? "" : safe(response.getLocation().getTimeZone());
			final String latitude = response.getLocation() == null || response.getLocation().getLatitude() == null
				? ""
				: Double.toString(response.getLocation().getLatitude());
			final String longitude = response.getLocation() == null || response.getLocation().getLongitude() == null
				? ""
				: Double.toString(response.getLocation().getLongitude());

			String region = "";
			if (response.getSubdivisions() != null && !response.getSubdivisions().isEmpty()) {
				region = safe(response.getSubdivisions().get(0).getName());
			}

			String asnNumber = "";
			String asnOrganization = "";
			if (asnReader != null) {
				try {
					final AsnResponse asnResponse = asnReader.asn(address);
					if (asnResponse.getAutonomousSystemNumber() != null) {
						asnNumber = "AS" + asnResponse.getAutonomousSystemNumber();
					}
					asnOrganization = safe(asnResponse.getAutonomousSystemOrganization());
				} catch (final Exception ignored) {
				}
			}

			return new GeoIpInfo(
				"success",
				country,
				countryCode,
				region,
				city,
				timezone,
				"",
				asnNumber,
				asnOrganization,
				latitude,
				longitude,
				""
			);
		} catch (final Exception exception) {
			logger.warn("GeoIP lookup exception for ip={}: {}", ip, exception.getMessage());
			return GeoIpInfo.unavailable();
		}
	}

	private void ensureDatabasePresent() throws IOException {
		if (Files.notExists(cityDatabaseFile) || isStale(cityDatabaseFile) || Files.notExists(asnDatabaseFile) || isStale(asnDatabaseFile)) {
			try {
				downloadDatabase();
			} catch (final IOException exception) {
				if (Files.exists(cityDatabaseFile)) {
					logger.warn("GeoIP database refresh failed. Using existing local database: {}", exception.getMessage());
					return;
				}
				throw exception;
			}
		}
	}

	private boolean isStale(final Path file) throws IOException {
		final Instant modifiedAt = Files.getLastModifiedTime(file).toInstant();
		return modifiedAt.plus(dbRefreshInterval).isBefore(Instant.now());
	}

	private void downloadDatabase() throws IOException {
		if (config.geoIpProvider() == PluginConfig.GeoIpProvider.MAXMIND_GEOLITE2) {
			downloadMaxMindGeoLite2();
			return;
		}

		downloadDbIp();
	}

	private void downloadDbIp() throws IOException {
		final String cityDownloadUrl = resolveDatePlaceholders(config.dbIpUrl());
		logger.info("Downloading DB-IP city GeoIP database from {}", cityDownloadUrl);
		downloadDbIpFile(
			cityDownloadUrl,
			cityDatabaseFile.resolveSibling("dbip-city-lite.mmdb.tmp"),
			cityDatabaseFile,
			"city"
		);

		if (!config.dbIpAsnUrl().isBlank()) {
			final String asnDownloadUrl = resolveDatePlaceholders(config.dbIpAsnUrl());
			logger.info("Downloading DB-IP ASN GeoIP database from {}", asnDownloadUrl);
			downloadDbIpFile(
				asnDownloadUrl,
				asnDatabaseFile.resolveSibling("dbip-asn-lite.mmdb.tmp"),
				asnDatabaseFile,
				"asn"
			);
		}
	}

	private void downloadDbIpFile(
		final String primaryUrl,
		final Path tmpFile,
		final Path targetFile,
		final String label
	) throws IOException {
		IOException lastError = null;
		for (final String candidateUrl : dbIpCandidateUrls(primaryUrl)) {
			if (candidateUrl == null || candidateUrl.isBlank()) {
				continue;
			}

			try {
				downloadAndExtractMmdb(candidateUrl, tmpFile, targetFile);
				return;
			} catch (final IOException exception) {
				lastError = exception;
				logger.warn("Failed to download DB-IP {} database from {}: {}", label, candidateUrl, exception.getMessage());
			}
		}

		throw new IOException("Unable to download DB-IP " + label + " database from configured URL(s)", lastError);
	}

	private static List<String> dbIpCandidateUrls(final String primaryUrl) {
		if (primaryUrl == null || primaryUrl.isBlank()) {
			return List.of();
		}

		final LinkedHashSet<String> candidates = new LinkedHashSet<>();
		candidates.add(primaryUrl);
		candidates.add(dbIpFallbackUrl(primaryUrl));

		if (primaryUrl.contains("-latest.")) {
			YearMonth probeMonth = YearMonth.now();
			for (int i = 0; i < 3; i++) {
				final String monthlyUrl = primaryUrl.replace("-latest.", "-" + probeMonth.format(YEAR_MONTH_FORMAT) + ".");
				candidates.add(monthlyUrl);
				candidates.add(dbIpFallbackUrl(monthlyUrl));
				probeMonth = probeMonth.minusMonths(1);
			}
		}

		return new ArrayList<>(candidates);
	}

	private void downloadMaxMindGeoLite2() throws IOException {
		if (config.maxMindLicenseKey().isBlank()) {
			throw new IOException("geoip.maxmind.license-key is missing in config.yml");
		}

		final String authorizationHeader = maxMindAuthorizationHeader();

		final String downloadUrl = config.maxMindUrlTemplate()
			.replace("{edition_id}", encode(config.maxMindEditionId()))
			.replace("{license_key}", encode(config.maxMindLicenseKey()));

		logger.info("Downloading MaxMind GeoLite2 city database (edition={})", config.maxMindEditionId());
		downloadAndExtractMaxMindMmdb(downloadUrl, cityDatabaseFile.resolveSibling("geolite2-city.mmdb.tmp"), cityDatabaseFile, authorizationHeader);

		if (!config.maxMindAsnEditionId().isBlank()) {
			final String asnDownloadUrl = config.maxMindUrlTemplate()
				.replace("{edition_id}", encode(config.maxMindAsnEditionId()))
				.replace("{license_key}", encode(config.maxMindLicenseKey()));

			logger.info("Downloading MaxMind GeoLite2 ASN database (edition={})", config.maxMindAsnEditionId());
			downloadAndExtractMaxMindMmdb(asnDownloadUrl, asnDatabaseFile.resolveSibling("geolite2-asn.mmdb.tmp"), asnDatabaseFile, authorizationHeader);
		}
	}

	private void downloadAndExtractMaxMindMmdb(
		final String downloadUrl,
		final Path tmpFile,
		final Path targetFile,
		final String authorizationHeader
	) throws IOException {
		final String normalized = downloadUrl.toLowerCase(Locale.ROOT);
		if (normalized.contains("suffix=zip") || normalized.endsWith(".zip")) {
			downloadAndExtractMmdbZip(downloadUrl, tmpFile, targetFile, authorizationHeader);
			return;
		}

		if (normalized.contains("suffix=tar.gz") || normalized.endsWith(".tar.gz") || normalized.contains("suffix=tgz")) {
			downloadAndExtractMmdbTarGz(downloadUrl, tmpFile, targetFile, authorizationHeader);
			return;
		}

		try {
			downloadAndExtractMmdbTarGz(downloadUrl, tmpFile, targetFile, authorizationHeader);
		} catch (final IOException tarException) {
			logger.warn("MaxMind tar.gz extraction failed for {}: {}", downloadUrl, tarException.getMessage());
			downloadAndExtractMmdbZip(downloadUrl, tmpFile, targetFile, authorizationHeader);
		}
	}

	private void downloadAndExtractMmdbZip(final String downloadUrl, final Path tmpFile, final Path targetFile, final String authorizationHeader) throws IOException {
		boolean extracted = false;

		try (InputStream stream = openHttpStream(downloadUrl, authorizationHeader);
			 ZipInputStream zipInputStream = new ZipInputStream(stream)) {

			ZipEntry entry;
			while ((entry = zipInputStream.getNextEntry()) != null) {
				if (!entry.isDirectory() && entry.getName().endsWith(".mmdb")) {
					try (OutputStream out = Files.newOutputStream(tmpFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
						zipInputStream.transferTo(out);
					}
					extracted = true;
					break;
				}
				zipInputStream.closeEntry();
			}
		}

		if (!extracted || Files.notExists(tmpFile)) {
			throw new IOException("No .mmdb file found in ZIP download");
		}

		Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		logger.info("GeoIP database saved to {}", targetFile.toAbsolutePath().toString());
	}

	private void downloadAndExtractMmdbTarGz(final String downloadUrl, final Path tmpFile, final Path targetFile, final String authorizationHeader) throws IOException {
		boolean extracted = false;

		try (InputStream stream = openHttpStream(downloadUrl, authorizationHeader);
			 GZIPInputStream gzipInputStream = new GZIPInputStream(stream);
			 TarArchiveInputStream tarInputStream = new TarArchiveInputStream(gzipInputStream)) {

			TarArchiveEntry entry;
			while ((entry = tarInputStream.getNextEntry()) != null) {
				if (!entry.isDirectory() && entry.getName().endsWith(".mmdb")) {
					try (OutputStream out = Files.newOutputStream(tmpFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
						tarInputStream.transferTo(out);
					}
					extracted = true;
					break;
				}
			}
		}

		if (!extracted || Files.notExists(tmpFile)) {
			throw new IOException("No .mmdb file found in tar.gz download");
		}

		Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		logger.info("GeoIP database saved to {}", targetFile.toAbsolutePath().toString());
	}

	private void downloadAndExtractGzip(final String downloadUrl, final Path tmpFile, final Path targetFile) throws IOException {
		try (InputStream stream = openHttpStream(downloadUrl);
			 GZIPInputStream gzip = new GZIPInputStream(stream);
			 OutputStream out = Files.newOutputStream(tmpFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			gzip.transferTo(out);
		}

		Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		logger.info("GeoIP database saved to {}", targetFile.toAbsolutePath().toString());
	}

	private void downloadAndExtractMmdb(final String downloadUrl, final Path tmpFile, final Path targetFile) throws IOException {
		if (downloadUrl.toLowerCase().endsWith(".gz")) {
			downloadAndExtractGzip(downloadUrl, tmpFile, targetFile);
			return;
		}

		try (InputStream stream = openHttpStream(downloadUrl);
			 OutputStream out = Files.newOutputStream(tmpFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
			stream.transferTo(out);
		}

		Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		logger.info("GeoIP database saved to {}", targetFile.toAbsolutePath().toString());
	}

	private static String cityDatabaseFileName(final PluginConfig.GeoIpProvider provider) {
		if (provider == PluginConfig.GeoIpProvider.MAXMIND_GEOLITE2) {
			return "geolite2-city.mmdb";
		}
		return "dbip-city-lite.mmdb";
	}

	private static String asnDatabaseFileName(final PluginConfig.GeoIpProvider provider) {
		if (provider == PluginConfig.GeoIpProvider.MAXMIND_GEOLITE2) {
			return "geolite2-asn.mmdb";
		}
		return "dbip-asn-lite.mmdb";
	}

	private static String encode(final String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String safe(final String value) {
		return value == null ? "" : value;
	}

	private static String dbIpFallbackUrl(final String url) {
		if (url == null || url.isBlank()) {
			return "";
		}

		if (url.endsWith(".mmdb.gz")) {
			return url.substring(0, url.length() - 3);
		}

		if (url.endsWith(".mmdb")) {
			return url + ".gz";
		}

		return "";
	}

	private static String resolveDatePlaceholders(final String url) {
		if (url == null || url.isBlank()) {
			return "";
		}

		final String currentYearMonth = YearMonth.now().format(YEAR_MONTH_FORMAT);
		return url
			.replace("YYYY-MM", currentYearMonth)
			.replace("yyyy-MM", currentYearMonth);
	}

	private String maxMindAuthorizationHeader() {
		if (config.maxMindAccountId().isBlank() || config.maxMindLicenseKey().isBlank()) {
			return null;
		}

		final String credentials = config.maxMindAccountId() + ":" + config.maxMindLicenseKey();
		final String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
		return "Basic " + encoded;
	}

	private InputStream openHttpStream(final String downloadUrl) throws IOException {
		return openHttpStream(downloadUrl, null);
	}

	private InputStream openHttpStream(final String downloadUrl, final String authorizationHeader) throws IOException {
		final HttpURLConnection connection = (HttpURLConnection) URI.create(downloadUrl).toURL().openConnection();
		connection.setInstanceFollowRedirects(true);
		connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
		connection.setReadTimeout(READ_TIMEOUT_MS);
		connection.setRequestProperty("User-Agent", "NamelessIPLogger/" + Constants.VERSION);
		connection.setRequestProperty("Accept", "*/*");
		if (authorizationHeader != null && !authorizationHeader.isBlank()) {
			connection.setRequestProperty("Authorization", authorizationHeader);
		}

		final int statusCode = connection.getResponseCode();
		if (statusCode >= 400) {
			connection.disconnect();
			if (statusCode == 401 && downloadUrl.contains("download.maxmind.com")) {
				throw new IOException(
					"HTTP 401 for " + downloadUrl
						+ " (MaxMind authentication failed: check geoip.maxmind.account-id, geoip.maxmind.license-key, and edition access)"
				);
			}
			throw new IOException("HTTP " + statusCode + " for " + downloadUrl);
		}

		return new BufferedInputStream(connection.getInputStream()) {
			@Override
			public void close() throws IOException {
				try {
					super.close();
				} finally {
					connection.disconnect();
				}
			}
		};
	}

	private void reopenReaders() throws IOException {
		closeReaders();

		cityReader = new DatabaseReader.Builder(cityDatabaseFile.toFile())
			.withCache(new CHMCache())
			.build();

		if (Files.exists(asnDatabaseFile)) {
			asnReader = new DatabaseReader.Builder(asnDatabaseFile.toFile())
				.withCache(new CHMCache())
				.build();
		}

		logger.info("GeoIP city database loaded from {}", cityDatabaseFile.toAbsolutePath().toString());
		if (asnReader != null) {
			logger.info("GeoIP ASN database loaded from {}", asnDatabaseFile.toAbsolutePath().toString());
		}
	}

	private void closeReaders() {
		if (cityReader != null) {
			try {
				cityReader.close();
			} catch (final IOException exception) {
				logger.warn("Failed to close GeoIP city database reader: {}", exception.getMessage());
			}
			cityReader = null;
		}
		if (asnReader != null) {
			try {
				asnReader.close();
			} catch (final IOException exception) {
				logger.warn("Failed to close GeoIP ASN database reader: {}", exception.getMessage());
			}
			asnReader = null;
		}
	}

	private boolean isPrivateAddress(final String ip) {
		try {
			final InetAddress address = InetAddress.getByName(ip);
			return address.isAnyLocalAddress()
				|| address.isLoopbackAddress()
				|| address.isLinkLocalAddress()
				|| address.isSiteLocalAddress();
		} catch (final UnknownHostException exception) {
			return false;
		}
	}

	private record CachedGeoIp(GeoIpInfo info, Instant expiresAt) {
	}
}

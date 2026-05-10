package dev.namelessnanashi.velocityiplogger;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Plugin(
	id = "velocityiplogger",
	name = "VelocityIPLogger",
	description = "Correlated IP, identity and connection logger with GeoIP lookups",
	version = Constants.VERSION,
	authors = { "NanashiTheNameless" }
)
public final class VelocityPlugin {
	@Inject
	private ComponentLogger logger;

	@Inject
	@DataDirectory
	private Path dataDirectory;

	@Inject
	private ProxyServer proxyServer;

	private ExecutorService executor;
	private IpLoggerRepository repository;
	private volatile GeoIpService geoIpService;
	private volatile TelemetryService telemetryService;
	private volatile UpdateCheckerService updateCheckerService;
	private volatile PluginConfig pluginConfig;
	private final Object lifecycleLock = new Object();
	
	@Subscribe
	void onProxyInitialization(final ProxyInitializeEvent event) {
		executor = Executors.newFixedThreadPool(2);

		try {
			pluginConfig = PluginConfig.load(dataDirectory, logger);
			repository = new IpLoggerRepository(dataDirectory, logger, pluginConfig);
			geoIpService = new GeoIpService(logger, dataDirectory, pluginConfig);
			repository.initialize();
			geoIpService.initialize();
			telemetryService = new TelemetryService(logger, dataDirectory, pluginConfig);
			telemetryService.start();
			updateCheckerService = new UpdateCheckerService(logger, proxyServer, pluginConfig);
			updateCheckerService.start();
			registerCommands();
			logger.info("VelocityIPLogger initialized. Data directory: {}", dataDirectory.toAbsolutePath().toString());
		} catch (final Exception exception) {
			logger.error("Failed to initialize VelocityIPLogger", exception);
		}
	}

	@Subscribe
	void onPostLogin(final PostLoginEvent event) {
		final Player player = event.getPlayer();
		if (updateCheckerService != null) {
			updateCheckerService.notifyPlayerIfOutdated(player);
		}
		final UUID uuid = player.getUniqueId();
		final String rawUsername = player.getUsername();
		final String rawIp = extractIp(player);
		final String username = pluginConfig.logIncludeUsername() ? rawUsername : "[redacted]";
		final String ip = pluginConfig.logIncludeIp() ? rawIp : "[redacted]";
		final Instant now = Instant.now();

		executor.submit(() -> {
			try {
				final GeoIpInfo geoIpInfo = pluginConfig.logIncludeGeoIp() && pluginConfig.logIncludeIp()
					? geoIpService.lookup(rawIp)
					: GeoIpInfo.unavailable();
				repository.recordConnect(uuid, username, ip, now, geoIpInfo);
				if (pluginConfig.logConsoleConnect()) {
					logger.info("Connected user={} uuid={} ip={} geo={}", username, uuid, ip, geoIpInfo.shortDescription());
				}
			} catch (final Exception exception) {
				logger.error("Failed to record connect event for {} ({})", rawUsername, uuid, exception);
			}
		});
	}

	@Subscribe
	void onDisconnect(final DisconnectEvent event) {
		final Player player = event.getPlayer();
		final UUID uuid = player.getUniqueId();
		final String rawUsername = player.getUsername();
		final String username = pluginConfig.logIncludeUsername() ? rawUsername : "[redacted]";
		final String ip = pluginConfig.logIncludeIp() ? extractIp(player) : "[redacted]";
		final Instant now = Instant.now();

		executor.submit(() -> {
			try {
				repository.recordDisconnect(uuid, username, ip, now, "DISCONNECT");
				if (pluginConfig.logConsoleDisconnect()) {
					logger.info("Disconnected user={} uuid={} ip={}", username, uuid, ip);
				}
			} catch (final Exception exception) {
				logger.error("Failed to record disconnect event for {} ({})", rawUsername, uuid, exception);
			}
		});
	}

	@Subscribe
	void onProxyShutdown(final ProxyShutdownEvent event) {
		if (executor != null) {
			executor.shutdown();
		}
		if (geoIpService != null) {
			geoIpService.shutdown();
		}
		if (telemetryService != null) {
			telemetryService.shutdown();
		}
		if (updateCheckerService != null) {
			updateCheckerService.shutdown();
		}
	}

	private String extractIp(final Player player) {
		if (!(player.getRemoteAddress() instanceof InetSocketAddress socketAddress)) {
			return "unknown";
		}

		final InetAddress address = socketAddress.getAddress();
		if (address == null) {
			return socketAddress.getHostString();
		}

		return address.getHostAddress();
	}

	private void registerCommands() {
		final CommandMeta lookupMeta = proxyServer.getCommandManager()
			.metaBuilder("viplookup")
			.aliases("viplog", "iplookup", "iplog")
			.plugin(this)
			.build();

		proxyServer.getCommandManager().register(lookupMeta, new ConsoleLookupCommand(repository, this));
	}

	public ReloadResult reloadConfiguration() {
		synchronized (lifecycleLock) {
			try {
				final PluginConfig newConfig = PluginConfig.load(dataDirectory, logger);
				final GeoIpService newGeoIpService = new GeoIpService(logger, dataDirectory, newConfig);
				newGeoIpService.initialize();
				if (!newGeoIpService.isReady()) {
					throw new IllegalStateException(newGeoIpService.lastInitializationError());
				}

				final GeoIpService previousService = geoIpService;
				pluginConfig = newConfig;
				geoIpService = newGeoIpService;
				if (repository != null) {
					repository.setConfig(newConfig);
				}
				if (telemetryService != null) {
					telemetryService.shutdown();
				}
				telemetryService = new TelemetryService(logger, dataDirectory, newConfig);
				telemetryService.start();
				if (updateCheckerService != null) {
					updateCheckerService.shutdown();
				}
				updateCheckerService = new UpdateCheckerService(logger, proxyServer, newConfig);
				updateCheckerService.start();
				if (previousService != null) {
					previousService.shutdown();
				}

				return new ReloadResult(true, "Configuration reloaded successfully.");
			} catch (final Exception exception) {
				logger.error("Failed to reload configuration", exception);
				return new ReloadResult(false, exception.getMessage() == null ? "unknown error" : exception.getMessage());
			}
		}
	}

	public ReloadResult updateGeoIpDatabaseNow() {
		synchronized (lifecycleLock) {
			if (geoIpService == null) {
				return new ReloadResult(false, "GeoIP service is not initialized yet.");
			}

			try {
				geoIpService.refreshNow();
				return new ReloadResult(true, "GeoIP databases were updated successfully.");
			} catch (final Exception exception) {
				logger.error("Failed to update GeoIP databases", exception);
				return new ReloadResult(false, exception.getMessage() == null ? "unknown error" : exception.getMessage());
			}
		}
	}

	public record ReloadResult(boolean success, String message) {
	}
}

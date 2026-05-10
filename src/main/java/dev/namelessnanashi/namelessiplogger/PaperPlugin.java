package dev.namelessnanashi.namelessiplogger;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PaperPlugin extends JavaPlugin implements Listener {
	private ComponentLogger logger;
	private ExecutorService executor;
	private IpLoggerRepository repository;
	private volatile GeoIpService geoIpService;
	private volatile TelemetryService telemetryService;
	private volatile PaperUpdateCheckerService updateCheckerService;
	private volatile PluginConfig pluginConfig;
	private volatile PluginStrings pluginStrings = PluginStrings.defaults();
	private final Object lifecycleLock = new Object();

	@Override
	public void onEnable() {
		logger = ComponentLogger.logger("NamelessIPLogger");
		executor = Executors.newFixedThreadPool(2);

		try {
			pluginConfig = PluginConfig.load(getDataFolder().toPath(), logger);
			pluginStrings = PluginStrings.load(getDataFolder().toPath(), logger);
			repository = new IpLoggerRepository(getDataFolder().toPath(), logger, pluginConfig);
			geoIpService = new GeoIpService(logger, getDataFolder().toPath(), pluginConfig);
			repository.initialize();
			geoIpService.initialize();
			telemetryService = new TelemetryService(logger, getDataFolder().toPath(), pluginConfig);
			telemetryService.start();
			updateCheckerService = new PaperUpdateCheckerService(logger, pluginConfig, pluginStrings);
			updateCheckerService.start();

			final PaperLookupCommand command = new PaperLookupCommand(repository, this);
			if (getCommand("niplookup") != null) {
				getCommand("niplookup").setExecutor(command);
				getCommand("niplookup").setTabCompleter(command);
			} else {
				logger.warn("Command niplookup is missing from plugin.yml");
			}

			Bukkit.getPluginManager().registerEvents(this, this);
			logger.info("NamelessIPLogger initialized on Paper. Data directory: {}",
					getDataFolder().toPath().toAbsolutePath().toString());
		} catch (final Exception exception) {
			logger.error("Failed to initialize NamelessIPLogger on Paper", exception);
			Bukkit.getPluginManager().disablePlugin(this);
		}
	}

	@Override
	public void onDisable() {
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

	@EventHandler
	public void onPlayerJoin(final PlayerJoinEvent event) {
		final Player player = event.getPlayer();
		if (updateCheckerService != null) {
			updateCheckerService.notifyPlayerIfOutdated(player);
		}

		final UUID uuid = player.getUniqueId();
		final String rawUsername = player.getName();
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
					logger.info("Connected user={} uuid={} ip={} geo={}", username, uuid, ip,
							geoIpInfo.shortDescription());
				}
			} catch (final Exception exception) {
				logger.error("Failed to record connect event for {} ({})", rawUsername, uuid, exception);
			}
		});
	}

	@EventHandler
	public void onPlayerQuit(final PlayerQuitEvent event) {
		final Player player = event.getPlayer();
		final UUID uuid = player.getUniqueId();
		final String rawUsername = player.getName();
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

	private String extractIp(final Player player) {
		final InetSocketAddress socketAddress = player.getAddress();
		if (socketAddress == null) {
			return "unknown";
		}

		final InetAddress address = socketAddress.getAddress();
		if (address == null) {
			return socketAddress.getHostString();
		}

		return address.getHostAddress();
	}

	boolean canUseLookupCommand(final CommandSender source) {
		return source instanceof ConsoleCommandSender
				|| (commandsAllowAdminPermission() && source.hasPermission(PluginPermissions.ADMIN));
	}

	public ReloadResult reloadConfiguration() {
		synchronized (lifecycleLock) {
			try {
				final PluginConfig newConfig = PluginConfig.load(getDataFolder().toPath(), logger);
				final PluginStrings newStrings = PluginStrings.load(getDataFolder().toPath(), logger);
				final GeoIpService newGeoIpService = new GeoIpService(logger, getDataFolder().toPath(), newConfig);
				newGeoIpService.initialize();
				if (!newGeoIpService.isReady()) {
					throw new IllegalStateException(newGeoIpService.lastInitializationError());
				}

				final GeoIpService previousService = geoIpService;
				pluginConfig = newConfig;
				pluginStrings = newStrings;
				geoIpService = newGeoIpService;
				if (repository != null) {
					repository.setConfig(newConfig);
				}
				if (telemetryService != null) {
					telemetryService.shutdown();
				}
				telemetryService = new TelemetryService(logger, getDataFolder().toPath(), newConfig);
				telemetryService.start();
				if (updateCheckerService != null) {
					updateCheckerService.shutdown();
				}
				updateCheckerService = new PaperUpdateCheckerService(logger, newConfig, newStrings);
				updateCheckerService.start();
				if (previousService != null) {
					previousService.shutdown();
				}

				return new ReloadResult(true, newStrings.get("reload.success"));
			} catch (final Exception exception) {
				logger.error("Failed to reload configuration", exception);
				return new ReloadResult(false, strings().format("reload.failure", "error", errorMessage(exception)));
			}
		}
	}

	public ReloadResult updateGeoIpDatabaseNow() {
		synchronized (lifecycleLock) {
			if (geoIpService == null) {
				return new ReloadResult(false, strings().get("geoip.service-unavailable"));
			}

			try {
				geoIpService.refreshNow();
				return new ReloadResult(true, strings().get("geoip.update.success"));
			} catch (final Exception exception) {
				logger.error("Failed to update GeoIP databases", exception);
				return new ReloadResult(false,
						strings().format("geoip.update.failure", "error", errorMessage(exception)));
			}
		}
	}

	public UpdateCheckResult checkForUpdatesNow() {
		final PaperUpdateCheckerService checker = updateCheckerService;
		if (checker == null) {
			return new UpdateCheckResult(false, false, strings().get("updates.checker-unavailable"));
		}

		final PaperUpdateCheckerService.UpdateCheckResult result = checker.checkNow();
		return new UpdateCheckResult(result.success(), result.updateAvailable(), result.message());
	}

	public boolean commandsAllowAdminPermission() {
		return pluginConfig != null && pluginConfig.commandsAllowAdminPermission();
	}

	public PluginStrings strings() {
		return pluginStrings == null ? PluginStrings.defaults() : pluginStrings;
	}

	private static String errorMessage(final Exception exception) {
		return exception.getMessage() == null ? "unknown error" : exception.getMessage();
	}

	public record ReloadResult(boolean success, String message) {
	}

	public record UpdateCheckResult(boolean success, boolean updateAvailable, String message) {
	}
}

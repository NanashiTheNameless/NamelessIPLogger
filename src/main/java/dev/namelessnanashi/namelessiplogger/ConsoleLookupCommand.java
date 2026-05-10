package dev.namelessnanashi.namelessiplogger;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.kyori.adventure.text.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ConsoleLookupCommand implements SimpleCommand {
	private final IpLoggerRepository repository;
	private final VelocityPlugin plugin;

	public ConsoleLookupCommand(final IpLoggerRepository repository, final VelocityPlugin plugin) {
		this.repository = repository;
		this.plugin = plugin;
	}

	@Override
	public void execute(final Invocation invocation) {
		if (!hasCommandAccess(invocation)) {
			sendAccessDenied(invocation);
			return;
		}

		final String[] args = invocation.arguments();
		if (args.length < 1) {
			sendUsage(invocation);
			return;
		}

		final String mode = args[0].toLowerCase();
		switch (mode) {
			case "uuid" -> {
				if (args.length < 2) {
					sendUsage(invocation);
					return;
				}
				handleUuid(invocation, args[1]);
			}
			case "username", "user", "name" -> {
				if (args.length < 2) {
					sendUsage(invocation);
					return;
				}
				handleUsername(invocation, args[1]);
			}
			case "ip" -> {
				if (args.length < 2) {
					sendUsage(invocation);
					return;
				}
				handleIp(invocation, args[1]);
			}
			case "reload" -> handleReload(invocation, args.length);
			case "updatedb", "update-db", "refreshdb" -> handleUpdateDb(invocation, args.length);
			case "checkupdates", "checkupdate", "updatecheck", "update-check", "updates" -> handleCheckUpdates(invocation, args.length);
			default -> sendUsage(invocation);
		}
	}

	@Override
	public List<String> suggest(final Invocation invocation) {
		if (!hasCommandAccess(invocation)) {
			return List.of();
		}

		final String[] args = invocation.arguments();
		if (args.length <= 1) {
			return Arrays.asList("uuid", "username", "ip", "reload", "updatedb", "checkupdates");
		}
		return List.of();
	}

	private boolean hasCommandAccess(final Invocation invocation) {
		return invocation.source() instanceof ConsoleCommandSource
			|| (plugin.commandsAllowAdminPermission() && invocation.source().hasPermission(PluginPermissions.ADMIN));
	}

	private void sendAccessDenied(final Invocation invocation) {
		if (plugin.commandsAllowAdminPermission()) {
			invocation.source().sendMessage(strings().component("access.permission-required", "permission", PluginPermissions.ADMIN));
			return;
		}

		invocation.source().sendMessage(strings().component("access.console-only"));
	}

	private void handleReload(final Invocation invocation, final int argLength) {
		if (argLength != 1) {
			invocation.source().sendMessage(strings().component("usage.reload"));
			return;
		}

		final VelocityPlugin.ReloadResult result = plugin.reloadConfiguration();
		invocation.source().sendMessage(prefixed(result.message()));
	}

	private void handleCheckUpdates(final Invocation invocation, final int argLength) {
		if (argLength != 1) {
			invocation.source().sendMessage(strings().component("usage.checkupdates"));
			return;
		}

		invocation.source().sendMessage(prefixed(strings().get("updates.check.start")));
		final VelocityPlugin.UpdateCheckResult result = plugin.checkForUpdatesNow();
		invocation.source().sendMessage(prefixed(result.message()));
	}

	private void handleUpdateDb(final Invocation invocation, final int argLength) {
		if (argLength != 1) {
			invocation.source().sendMessage(strings().component("usage.updatedb"));
			return;
		}

		invocation.source().sendMessage(prefixed(strings().get("geoip.update.start")));
		final VelocityPlugin.ReloadResult result = plugin.updateGeoIpDatabaseNow();
		invocation.source().sendMessage(prefixed(result.message()));
	}

	private void handleUuid(final Invocation invocation, final String rawUuid) {
		final UUID uuid;
		try {
			uuid = UUID.fromString(rawUuid);
		} catch (final IllegalArgumentException exception) {
			invocation.source().sendMessage(strings().component("lookup.invalid-uuid", "uuid", rawUuid));
			return;
		}

		final Optional<IpLoggerRepository.PlayerInfoView> result = repository.findByUuid(uuid);
		if (result.isEmpty()) {
			invocation.source().sendMessage(strings().component("lookup.no-records.uuid", "uuid", uuid.toString()));
			return;
		}

		sendPlayerInfo(invocation, result.get());
	}

	private void handleUsername(final Invocation invocation, final String username) {
		final List<IpLoggerRepository.PlayerInfoView> players = repository.findByUsername(username);
		if (players.isEmpty()) {
			invocation.source().sendMessage(strings().component("lookup.no-records.username", "username", username));
			return;
		}

		invocation.source().sendMessage(strings().component("lookup.matches.username", "username", username, "count", Integer.toString(players.size())));
		for (final IpLoggerRepository.PlayerInfoView player : players) {
			sendPlayerInfo(invocation, player);
		}
	}

	private void handleIp(final Invocation invocation, final String ip) {
		final List<IpLoggerRepository.IpCorrelationView> links = repository.findByIp(ip);
		if (links.isEmpty()) {
			invocation.source().sendMessage(strings().component("lookup.no-records.ip", "ip", ip));
			return;
		}

		invocation.source().sendMessage(strings().component("lookup.matches.ip", "ip", ip, "count", Integer.toString(links.size())));
		for (final IpLoggerRepository.IpCorrelationView link : links) {
			invocation.source().sendMessage(strings().component(
				"lookup.ip-correlation",
				"uuid", link.uuid().toString(),
				"username", link.username(),
				"first_seen", String.valueOf(link.firstSeen()),
				"last_seen", String.valueOf(link.lastSeen()),
				"times_seen", Long.toString(link.timesSeen())
			));
			invocation.source().sendMessage(strings().component(
				"lookup.geo",
				"geo", summarizeGeo(link.geoStatus(), link.city(), link.region(), link.country(), link.countryCode(), link.timezone(), link.latitude(), link.longitude(), link.geoMessage())
			));
		}
	}

	private void sendPlayerInfo(final Invocation invocation, final IpLoggerRepository.PlayerInfoView player) {
		invocation.source().sendMessage(strings().component(
			"lookup.player",
			"uuid", player.uuid().toString(),
			"username", player.username(),
			"first_seen", String.valueOf(player.firstSeen()),
			"last_seen", String.valueOf(player.lastSeen()),
			"last_ip", player.lastIp()
		));

		if (player.ipLinks().isEmpty()) {
			invocation.source().sendMessage(strings().component("lookup.no-ip-links"));
			return;
		}

		invocation.source().sendMessage(strings().component("lookup.linked-ips", "count", Integer.toString(player.ipLinks().size())));
		for (final IpLoggerRepository.IpLinkView ipLink : player.ipLinks()) {
			invocation.source().sendMessage(strings().component(
				"lookup.ip-link",
				"ip", ipLink.ip(),
				"first_seen", String.valueOf(ipLink.firstSeen()),
				"last_seen", String.valueOf(ipLink.lastSeen()),
				"times_seen", Long.toString(ipLink.timesSeen())
			));
			invocation.source().sendMessage(strings().component(
				"lookup.geo.indented",
				"geo", summarizeGeo(ipLink.geoStatus(), ipLink.city(), ipLink.region(), ipLink.country(), ipLink.countryCode(), ipLink.timezone(), ipLink.latitude(), ipLink.longitude(), ipLink.geoMessage())
			));
		}
	}

	private String summarizeGeo(
		final String status,
		final String city,
		final String region,
		final String country,
		final String countryCode,
		final String timezone,
		final String latitude,
		final String longitude,
		final String message
	) {
		return strings().format(
			"lookup.geo-summary",
			"status", nullToEmpty(status),
			"city", nullToEmpty(city),
			"region", nullToEmpty(region),
			"country", nullToEmpty(country),
			"country_code", nullToEmpty(countryCode),
			"timezone", nullToEmpty(timezone),
			"lat", nullToEmpty(latitude),
			"lon", nullToEmpty(longitude),
			"message", nullToEmpty(message)
		);
	}

	private String nullToEmpty(final String value) {
		return value == null ? "" : value;
	}

	private void sendUsage(final Invocation invocation) {
		invocation.source().sendMessage(strings().component("usage.lookup"));
		invocation.source().sendMessage(strings().component("usage.reload"));
		invocation.source().sendMessage(strings().component("usage.updatedb"));
		invocation.source().sendMessage(strings().component("usage.checkupdates"));
	}

	private Component prefixed(final String message) {
		return Component.text(strings().get("prefix") + " " + message);
	}

	private PluginStrings strings() {
		return plugin.strings();
	}
}

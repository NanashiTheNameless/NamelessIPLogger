package dev.namelessnanashi.namelessiplogger;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PaperLookupCommand implements CommandExecutor, TabCompleter {
	private final IpLoggerRepository repository;
	private final PaperPlugin plugin;

	public PaperLookupCommand(final IpLoggerRepository repository, final PaperPlugin plugin) {
		this.repository = repository;
		this.plugin = plugin;
	}

	@Override
	public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
		if (!hasCommandAccess(sender)) {
			sendAccessDenied(sender);
			return true;
		}

		if (args.length < 1) {
			sendUsage(sender);
			return true;
		}

		final String mode = args[0].toLowerCase();
		switch (mode) {
			case "uuid" -> {
				if (args.length < 2) {
					sendUsage(sender);
					return true;
				}
				handleUuid(sender, args[1]);
			}
			case "username", "user", "name" -> {
				if (args.length < 2) {
					sendUsage(sender);
					return true;
				}
				handleUsername(sender, args[1]);
			}
			case "ip" -> {
				if (args.length < 2) {
					sendUsage(sender);
					return true;
				}
				handleIp(sender, args[1]);
			}
			case "reload" -> handleReload(sender, args.length);
			case "updatedb", "update-db", "refreshdb" -> handleUpdateDb(sender, args.length);
			case "checkupdates", "checkupdate", "updatecheck", "update-check", "updates" -> handleCheckUpdates(sender, args.length);
			default -> sendUsage(sender);
		}

		return true;
	}

	@Override
	public List<String> onTabComplete(final CommandSender sender, final Command command, final String alias, final String[] args) {
		if (!hasCommandAccess(sender)) {
			return List.of();
		}

		if (args.length <= 1) {
			return Arrays.asList("uuid", "username", "ip", "reload", "updatedb", "checkupdates");
		}
		return List.of();
	}

	private boolean hasCommandAccess(final CommandSender sender) {
		return plugin.canUseLookupCommand(sender);
	}

	private void sendAccessDenied(final CommandSender sender) {
		if (plugin.commandsAllowAdminPermission()) {
			sender.sendMessage(strings().format("access.permission-required", "permission", PluginPermissions.ADMIN));
			return;
		}

		sender.sendMessage(strings().get("access.console-only"));
	}

	private void handleReload(final CommandSender sender, final int argLength) {
		if (argLength != 1) {
			sender.sendMessage(strings().get("usage.reload"));
			return;
		}

		final PaperPlugin.ReloadResult result = plugin.reloadConfiguration();
		sender.sendMessage(prefixed(result.message()));
	}

	private void handleCheckUpdates(final CommandSender sender, final int argLength) {
		if (argLength != 1) {
			sender.sendMessage(strings().get("usage.checkupdates"));
			return;
		}

		sender.sendMessage(prefixed(strings().get("updates.check.start")));
		final PaperPlugin.UpdateCheckResult result = plugin.checkForUpdatesNow();
		sender.sendMessage(prefixed(result.message()));
	}

	private void handleUpdateDb(final CommandSender sender, final int argLength) {
		if (argLength != 1) {
			sender.sendMessage(strings().get("usage.updatedb"));
			return;
		}

		sender.sendMessage(prefixed(strings().get("geoip.update.start")));
		final PaperPlugin.ReloadResult result = plugin.updateGeoIpDatabaseNow();
		sender.sendMessage(prefixed(result.message()));
	}

	private void handleUuid(final CommandSender sender, final String rawUuid) {
		final UUID uuid;
		try {
			uuid = UUID.fromString(rawUuid);
		} catch (final IllegalArgumentException exception) {
			sender.sendMessage(strings().format("lookup.invalid-uuid", "uuid", rawUuid));
			return;
		}

		final Optional<IpLoggerRepository.PlayerInfoView> result = repository.findByUuid(uuid);
		if (result.isEmpty()) {
			sender.sendMessage(strings().format("lookup.no-records.uuid", "uuid", uuid.toString()));
			return;
		}

		sendPlayerInfo(sender, result.get());
	}

	private void handleUsername(final CommandSender sender, final String username) {
		final List<IpLoggerRepository.PlayerInfoView> players = repository.findByUsername(username);
		if (players.isEmpty()) {
			sender.sendMessage(strings().format("lookup.no-records.username", "username", username));
			return;
		}

		sender.sendMessage(strings().format("lookup.matches.username", "username", username, "count", Integer.toString(players.size())));
		for (final IpLoggerRepository.PlayerInfoView player : players) {
			sendPlayerInfo(sender, player);
		}
	}

	private void handleIp(final CommandSender sender, final String ip) {
		final List<IpLoggerRepository.IpCorrelationView> links = repository.findByIp(ip);
		if (links.isEmpty()) {
			sender.sendMessage(strings().format("lookup.no-records.ip", "ip", ip));
			return;
		}

		sender.sendMessage(strings().format("lookup.matches.ip", "ip", ip, "count", Integer.toString(links.size())));
		for (final IpLoggerRepository.IpCorrelationView link : links) {
			sender.sendMessage(strings().format(
				"lookup.ip-correlation",
				"uuid", link.uuid().toString(),
				"username", link.username(),
				"first_seen", String.valueOf(link.firstSeen()),
				"last_seen", String.valueOf(link.lastSeen()),
				"times_seen", Long.toString(link.timesSeen())
			));
			sender.sendMessage(strings().format(
				"lookup.geo",
				"geo", summarizeGeo(link.geoStatus(), link.city(), link.region(), link.country(), link.countryCode(), link.timezone(), link.latitude(), link.longitude(), link.geoMessage())
			));
		}
	}

	private void sendPlayerInfo(final CommandSender sender, final IpLoggerRepository.PlayerInfoView player) {
		sender.sendMessage(strings().format(
			"lookup.player",
			"uuid", player.uuid().toString(),
			"username", player.username(),
			"first_seen", String.valueOf(player.firstSeen()),
			"last_seen", String.valueOf(player.lastSeen()),
			"last_ip", player.lastIp()
		));

		if (player.ipLinks().isEmpty()) {
			sender.sendMessage(strings().get("lookup.no-ip-links"));
			return;
		}

		sender.sendMessage(strings().format("lookup.linked-ips", "count", Integer.toString(player.ipLinks().size())));
		for (final IpLoggerRepository.IpLinkView ipLink : player.ipLinks()) {
			sender.sendMessage(strings().format(
				"lookup.ip-link",
				"ip", ipLink.ip(),
				"first_seen", String.valueOf(ipLink.firstSeen()),
				"last_seen", String.valueOf(ipLink.lastSeen()),
				"times_seen", Long.toString(ipLink.timesSeen())
			));
			sender.sendMessage(strings().format(
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

	private void sendUsage(final CommandSender sender) {
		sender.sendMessage(strings().get("usage.lookup"));
		sender.sendMessage(strings().get("usage.reload"));
		sender.sendMessage(strings().get("usage.updatedb"));
		sender.sendMessage(strings().get("usage.checkupdates"));
	}

	private String prefixed(final String message) {
		return strings().get("prefix") + " " + message;
	}

	private PluginStrings strings() {
		return plugin.strings();
	}
}

package dev.namelessnanashi.velocityiplogger;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import net.kyori.adventure.text.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ConsoleLookupCommand implements SimpleCommand {
	private static final String ADMIN_PERMISSION = "velocityiplogger.admin";

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
			|| (plugin.commandsAllowAdminPermission() && invocation.source().hasPermission(ADMIN_PERMISSION));
	}

	private void sendAccessDenied(final Invocation invocation) {
		if (plugin.commandsAllowAdminPermission()) {
			invocation.source().sendMessage(Component.text("This command requires " + ADMIN_PERMISSION + "."));
			return;
		}

		invocation.source().sendMessage(Component.text("This command is console-only."));
	}

	private void handleReload(final Invocation invocation, final int argLength) {
		if (argLength != 1) {
			invocation.source().sendMessage(Component.text("Usage: /viplookup reload"));
			return;
		}

		final VelocityPlugin.ReloadResult result = plugin.reloadConfiguration();
		if (result.success()) {
			invocation.source().sendMessage(Component.text("[VelocityIPLogger] " + result.message()));
		} else {
			invocation.source().sendMessage(Component.text("[VelocityIPLogger] Reload failed: " + result.message()));
		}
	}

	private void handleCheckUpdates(final Invocation invocation, final int argLength) {
		if (argLength != 1) {
			invocation.source().sendMessage(Component.text("Usage: /viplookup checkupdates"));
			return;
		}

		invocation.source().sendMessage(Component.text("[VelocityIPLogger] Checking for updates now..."));
		final VelocityPlugin.UpdateCheckResult result = plugin.checkForUpdatesNow();
		invocation.source().sendMessage(Component.text("[VelocityIPLogger] " + result.message()));
	}

	private void handleUpdateDb(final Invocation invocation, final int argLength) {
		if (argLength != 1) {
			invocation.source().sendMessage(Component.text("Usage: /viplookup updatedb"));
			return;
		}

		invocation.source().sendMessage(Component.text("[VelocityIPLogger] Updating GeoIP databases now..."));
		final VelocityPlugin.ReloadResult result = plugin.updateGeoIpDatabaseNow();
		if (result.success()) {
			invocation.source().sendMessage(Component.text("[VelocityIPLogger] " + result.message()));
		} else {
			invocation.source().sendMessage(Component.text("[VelocityIPLogger] Update failed: " + result.message()));
		}
	}

	private void handleUuid(final Invocation invocation, final String rawUuid) {
		final UUID uuid;
		try {
			uuid = UUID.fromString(rawUuid);
		} catch (final IllegalArgumentException exception) {
			invocation.source().sendMessage(Component.text("Invalid UUID: " + rawUuid));
			return;
		}

		final Optional<IpLoggerRepository.PlayerInfoView> result = repository.findByUuid(uuid);
		if (result.isEmpty()) {
			invocation.source().sendMessage(Component.text("No records found for UUID " + uuid));
			return;
		}

		sendPlayerInfo(invocation, result.get());
	}

	private void handleUsername(final Invocation invocation, final String username) {
		final List<IpLoggerRepository.PlayerInfoView> players = repository.findByUsername(username);
		if (players.isEmpty()) {
			invocation.source().sendMessage(Component.text("No records found for username " + username));
			return;
		}

		invocation.source().sendMessage(Component.text("Matches for username '" + username + "': " + players.size()));
		for (final IpLoggerRepository.PlayerInfoView player : players) {
			sendPlayerInfo(invocation, player);
		}
	}

	private void handleIp(final Invocation invocation, final String ip) {
		final List<IpLoggerRepository.IpCorrelationView> links = repository.findByIp(ip);
		if (links.isEmpty()) {
			invocation.source().sendMessage(Component.text("No records found for IP " + ip));
			return;
		}

		invocation.source().sendMessage(Component.text("Matches for IP " + ip + ": " + links.size()));
		for (final IpLoggerRepository.IpCorrelationView link : links) {
			invocation.source().sendMessage(Component.text(
				"- uuid=" + link.uuid()
					+ " username=" + link.username()
					+ " firstSeen=" + link.firstSeen()
					+ " lastSeen=" + link.lastSeen()
					+ " timesSeen=" + link.timesSeen()
			));
			invocation.source().sendMessage(Component.text(
				"  geo=" + summarizeGeo(link.geoStatus(), link.city(), link.region(), link.country(), link.countryCode(), link.timezone(), link.latitude(), link.longitude(), link.geoMessage())
			));
		}
	}

	private void sendPlayerInfo(final Invocation invocation, final IpLoggerRepository.PlayerInfoView player) {
		invocation.source().sendMessage(Component.text(
			"Player uuid=" + player.uuid()
				+ " username=" + player.username()
				+ " firstSeen=" + player.firstSeen()
				+ " lastSeen=" + player.lastSeen()
				+ " lastIp=" + player.lastIp()
		));

		if (player.ipLinks().isEmpty()) {
			invocation.source().sendMessage(Component.text("  No IP links recorded."));
			return;
		}

		invocation.source().sendMessage(Component.text("  Linked IPs: " + player.ipLinks().size()));
		for (final IpLoggerRepository.IpLinkView ipLink : player.ipLinks()) {
			invocation.source().sendMessage(Component.text(
				"  - ip=" + ipLink.ip()
					+ " firstSeen=" + ipLink.firstSeen()
					+ " lastSeen=" + ipLink.lastSeen()
					+ " timesSeen=" + ipLink.timesSeen()
			));
			invocation.source().sendMessage(Component.text(
				"    geo=" + summarizeGeo(ipLink.geoStatus(), ipLink.city(), ipLink.region(), ipLink.country(), ipLink.countryCode(), ipLink.timezone(), ipLink.latitude(), ipLink.longitude(), ipLink.geoMessage())
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
		return "status=" + nullToEmpty(status)
			+ " city=" + nullToEmpty(city)
			+ " region=" + nullToEmpty(region)
			+ " country=" + nullToEmpty(country)
			+ " countryCode=" + nullToEmpty(countryCode)
			+ " timezone=" + nullToEmpty(timezone)
			+ " lat=" + nullToEmpty(latitude)
			+ " lon=" + nullToEmpty(longitude)
			+ " message=" + nullToEmpty(message);
	}

	private String nullToEmpty(final String value) {
		return value == null ? "" : value;
	}

	private void sendUsage(final Invocation invocation) {
		invocation.source().sendMessage(Component.text("Usage: /viplookup <uuid|username|ip> <value>"));
		invocation.source().sendMessage(Component.text("Usage: /viplookup reload"));
		invocation.source().sendMessage(Component.text("Usage: /viplookup updatedb"));
		invocation.source().sendMessage(Component.text("Usage: /viplookup checkupdates"));
	}
}

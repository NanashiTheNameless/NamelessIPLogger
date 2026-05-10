package dev.namelessnanashi.velocityiplogger;

import java.util.List;

public final class PluginPermissions {
	public static final String ADMIN = "velocityiplogger.admin";
	public static final String UPDATE_NOTIFY = "velocityiplogger.update.notify";
	public static final List<String> ALL = List.of(ADMIN, UPDATE_NOTIFY);

	private PluginPermissions() {
	}
}

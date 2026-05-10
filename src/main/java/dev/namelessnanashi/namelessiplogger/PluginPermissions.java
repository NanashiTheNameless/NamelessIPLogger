package dev.namelessnanashi.namelessiplogger;

import java.util.List;

public final class PluginPermissions {
	public static final String ADMIN = "namelessiplogger.admin";
	public static final String UPDATE_NOTIFY = "namelessiplogger.update.notify";
	public static final List<String> ALL = List.of(ADMIN, UPDATE_NOTIFY);

	private PluginPermissions() {
	}
}

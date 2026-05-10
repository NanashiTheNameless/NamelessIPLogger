package dev.namelessnanashi.namelessiplogger;

import be.seeseemelk.mockbukkit.MockBukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class MockBukkitSetupTest {

	@AfterEach
	void tearDown() {
		MockBukkit.unmock();
	}

	@Test
	void startsMockServer() {
		final Server server = MockBukkit.mock();
		assertNotNull(server);
	}
}

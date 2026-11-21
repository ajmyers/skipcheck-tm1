package net.ajmyers.tm1.client.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class Tm1TimeoutsTest {

	@Test
	void fillsInDefaults() {
		assertThat(Tm1Timeouts.DEFAULTS).isEqualTo(new Tm1Timeouts(Duration.ofSeconds(10), Duration.ofSeconds(30),
				Duration.ofMinutes(10), Duration.ofSeconds(5)));
		Tm1Timeouts longRequests = Tm1Timeouts.builder().request(Duration.ofHours(1)).build();
		assertThat(longRequests.request()).isEqualTo(Duration.ofHours(1));
		assertThat(longRequests.login()).isEqualTo(Duration.ofSeconds(30));
		assertThat(longRequests.toBuilder().close(Duration.ofSeconds(1)).build().close())
			.isEqualTo(Duration.ofSeconds(1));
	}

	@Test
	void refusesTimeoutsThatArentPositive() {
		assertThatIllegalArgumentException().isThrownBy(() -> Tm1Timeouts.builder().login(Duration.ZERO).build());
		assertThatIllegalArgumentException()
			.isThrownBy(() -> Tm1Timeouts.builder().connect(Duration.ofSeconds(-1)).build());
	}

}

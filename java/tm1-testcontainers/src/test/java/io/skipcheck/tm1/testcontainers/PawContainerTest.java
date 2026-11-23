// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

@RequiresPaw
class PawContainerTest {

	@Test
	void logsOnAndCallsTm1ThroughPaw(PawContainer paw, Tm1ServerContainer tm1) throws Exception {
		var session = new PawSession(paw.getUrl()).login(tm1.getUsername(), tm1.getPassword());
		String server = Tm1WebContainerTest.serverName(tm1);

		assertThat(session.servers()).contains(server);
		HttpResponse<String> user = session.tm1Get(server, "ActiveUser?$select=Name");
		assertThat(user.statusCode()).isEqualTo(200);
		assertThat(user.body()).containsIgnoringCase("\"Name\":\"" + tm1.getUsername() + "\"");
	}

	@Test
	void servesTheWorkspaceAppOnceLoggedOn(PawContainer paw, Tm1ServerContainer tm1) throws Exception {
		var session = new PawSession(paw.getUrl()).login(tm1.getUsername(), tm1.getPassword());

		HttpResponse<String> app = session.get("");
		assertThat(app.statusCode()).isEqualTo(200);
		assertThat(app.body()).contains("Planning Analytics");
	}

	@Test
	void refusesAWrongPassword(PawContainer paw, Tm1ServerContainer tm1) {
		assertThatIOException()
			.isThrownBy(() -> new PawSession(paw.getUrl()).login(tm1.getUsername(), "not-the-password"));
	}

	@Test
	void sharesOnePawWiredToTheSharedServer(PawContainer paw, Tm1ServerContainer tm1) {
		assertThat(paw).isSameAs(PawContainer.shared());
		assertThat(paw.getTm1Server()).isSameAs(tm1);
		assertThat(paw.isRunning()).isTrue();
		assertThat(paw.getUrl()).startsWith("http://").endsWith("/");
	}

}

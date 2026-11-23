// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Network;

/** Configuration only: nothing here starts a container. */
class AdminServerWiringTest {

	private final Tm1Source source = new Tm1Source.PrivateImage("tm1-server:test");

	@Test
	void serversRegisterWithTheAdminServerOnItsNetwork() {
		var admin = new Tm1AdminServerContainer(source);
		var planning = new Tm1ServerContainer(source).withAdminServer(admin);
		var reporting = new Tm1ServerContainer(source).withAdminServer(admin);

		assertThat(admin.getCommandParts()).containsExactly("admin");
		assertThat(admin.getNetworkAliases()).contains(Tm1AdminServerContainer.NETWORK_ALIAS);
		assertThat(planning.getEnvMap()).containsEntry("TM1_ADMIN_HOST", Tm1AdminServerContainer.NETWORK_ALIAS);
		assertThat(planning.getNetwork()).isNotNull().isSameAs(admin.getNetwork()).isSameAs(reporting.getNetwork());
		assertThat(planning.getDependencies()).contains(admin);
		assertThat(planning.getAdminServer()).isSameAs(admin);
	}

	@Test
	void tm1WebListsServersFromTheAdminServer() {
		var admin = new Tm1AdminServerContainer(source);
		var web = new Tm1WebContainer(new Tm1WebSource.PrivateImage("tm1-web:test")).withAdminServer(admin);
		web.configure();

		assertThat(web.getEnvMap()).containsEntry("TM1_ADMIN_HOST", Tm1AdminServerContainer.NETWORK_ALIAS);
		assertThat(web.getNetwork()).isSameAs(admin.getNetwork());
		assertThat(web.getAdminServer()).isSameAs(admin);
	}

	@Test
	void joinsANetworkEitherSideAlreadyHas() {
		Network network = Network.newNetwork();
		var admin = new Tm1AdminServerContainer(source);
		var tm1 = new Tm1ServerContainer(source).withNetwork(network).withAdminServer(admin);
		assertThat(admin.getNetwork()).isSameAs(network);
		assertThat(tm1.getNetwork()).isSameAs(network);
	}

	@Test
	void refusesContainersOnDifferentNetworks() {
		var admin = new Tm1AdminServerContainer(source).withNetwork(Network.newNetwork());
		var tm1 = new Tm1ServerContainer(source).withNetwork(Network.newNetwork());
		assertThatIllegalStateException().isThrownBy(() -> tm1.withAdminServer(admin))
			.withMessageContaining("share one Network");
	}

}

// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.auth.Tm1Authenticator;
import io.skipcheck.tm1.client.session.Tm1Session;
import io.skipcheck.tm1.client.session.Tm1SessionClosedException;
import io.skipcheck.tm1.client.session.Tm1SessionPool;
import io.skipcheck.tm1.client.version.Tm1Dialect;

class Tm1AutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(Tm1AutoConfiguration.class));

	@Test
	void doesNothingUntilAServerIsConfigured() {
		runner.run(context -> assertThat(context).doesNotHaveBean(Tm1ConnectionDetails.class)
			.doesNotHaveBean(Tm1Session.class)
			.doesNotHaveBean(Tm1Client.class));
	}

	@Test
	void connectsToTheConfiguredAddress() {
		runner.withPropertyValues("tm1.address=http://tm1:8010", "tm1.user=admin").run(context -> {
			assertThat(context).hasSingleBean(PropertiesTm1ConnectionDetails.class)
				.hasSingleBean(Tm1Client.class)
				.doesNotHaveBean(Tm1SessionPool.class);
			assertThat(context.getBean(Tm1Session.class).serviceRoot()).isEqualTo("http://tm1:8010/api/v1/");
			assertThat(context.getBean(Tm1Client.class).session()).isSameAs(context.getBean(Tm1Session.class));
			assertThat(context.getBean(Tm1ConnectionDetails.class).getAuthenticator().describe())
				.isEqualTo("Basic admin");
		});
	}

	@Test
	void prefersTheBaseUrlOverTheAddress() {
		runner
			.withPropertyValues("tm1.address=http://tm1:8010", "tm1.base-url=https://proxy/tm1/api/v1/",
					"tm1.user=admin")
			.run(context -> assertThat(context.getBean(Tm1Session.class).serviceRoot())
				.isEqualTo("https://proxy/tm1/api/v1/"));
	}

	@Test
	void logsInWithCamWhenANamespaceIsSet() {
		runner
			.withPropertyValues("tm1.address=http://tm1:8010", "tm1.user=alice", "tm1.password=secret",
					"tm1.namespace=LDAP")
			.run(context -> assertThat(context.getBean(Tm1ConnectionDetails.class).getAuthenticator().describe())
				.isEqualTo("CAMNamespace LDAP/alice"));
	}

	@Test
	void getsCamPassportsFromCognosWhenItsUrlIsSet() {
		runner
			.withPropertyValues("tm1.address=http://tm1:8010", "tm1.user=alice", "tm1.password=secret",
					"tm1.namespace=LDAP", "tm1.cognos-url=http://cognos:9300")
			.run(context -> assertThat(context.getBean(Tm1ConnectionDetails.class).getAuthenticator().describe())
				.isEqualTo("CAMPassport from Cognos LDAP/alice"));
	}

	@Test
	void connectsToTm1V12WithApplicationCredentials() {
		runner
			.withPropertyValues("tm1.address=http://tm1:4444", "tm1.instance=tm1", "tm1.database=planning",
					"tm1.user=admin", "tm1.client-id=app-1", "tm1.client-secret=s3cr3t", "tm1.pool.enabled=true")
			.run(context -> {
				Tm1ConnectionDetails details = context.getBean(Tm1ConnectionDetails.class);
				assertThat(details.getDialect()).isEqualTo(Tm1Dialect.v12("tm1", "planning"));
				assertThat(details.getServiceRoot()).isEqualTo("http://tm1:4444/tm1/api/v1/Databases('planning')/");
				assertThat(details.getAuthenticator().describe()).isEqualTo("Application app-1 as admin");
				assertThat(context.getBean(Tm1Session.class).serviceRoot())
					.isEqualTo("http://tm1:4444/tm1/api/v1/Databases('planning')/");
				assertThat(context.getBean(Tm1SessionPool.class).session().serviceRoot())
					.isEqualTo("http://tm1:4444/tm1/api/v1/Databases('planning')/");
			});
	}

	@Test
	void connectsToTm1V12WithBaseUrlAndBearerToken() {
		runner
			.withPropertyValues("tm1.base-url=https://proxy/tm1/api/v1/Databases('planning')/", "tm1.instance=tm1",
					"tm1.database=planning", "tm1.bearer-token=eyJ.test.token")
			.run(context -> {
				Tm1ConnectionDetails details = context.getBean(Tm1ConnectionDetails.class);
				assertThat(details.getDialect()).isEqualTo(Tm1Dialect.v12("tm1", "planning"));
				assertThat(details.getServiceRoot()).isEqualTo("https://proxy/tm1/api/v1/Databases('planning')/");
				assertThat(details.getAuthenticator().describe()).isEqualTo("Bearer");
				assertThat(context.getBean(Tm1Session.class).serviceRoot())
					.isEqualTo("https://proxy/tm1/api/v1/Databases('planning')/");
			});
	}

	@Test
	void connectsToTm1V12WithBasicPassThrough() {
		runner
			.withPropertyValues("tm1.address=http://tm1:4444", "tm1.instance=tm1", "tm1.database=planning",
					"tm1.user=analyst", "tm1.password=pw")
			.run(context -> {
				Tm1ConnectionDetails details = context.getBean(Tm1ConnectionDetails.class);
				assertThat(details.getDialect()).isEqualTo(Tm1Dialect.v12("tm1", "planning"));
				assertThat(details.getServiceRoot()).isEqualTo("http://tm1:4444/tm1/api/v1/Databases('planning')/");
				assertThat(details.getAuthenticator().describe()).isEqualTo("Basic analyst");
			});
	}

	@Test
	void explainsPartialV12DialectOrConflictingAuthProperties() {
		runner.withPropertyValues("tm1.address=http://tm1:4444", "tm1.instance=tm1", "tm1.user=admin")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("Set both tm1.instance and tm1.database"));

		runner.withPropertyValues("tm1.address=http://tm1:4444", "tm1.database=planning", "tm1.user=admin")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("Set both tm1.instance and tm1.database"));

		runner
			.withPropertyValues("tm1.address=http://tm1:4444", "tm1.instance=tm1", "tm1.database=planning",
					"tm1.user=admin", "tm1.client-id=app-1")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("Set both tm1.client-id and tm1.client-secret"));

		runner
			.withPropertyValues("tm1.address=http://tm1:4444", "tm1.instance=tm1", "tm1.database=planning",
					"tm1.user=admin", "tm1.client-secret=s3cr3t")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("Set both tm1.client-id and tm1.client-secret"));

		runner
			.withPropertyValues("tm1.address=http://tm1:4444", "tm1.instance=tm1", "tm1.database=planning",
					"tm1.client-id=app-1", "tm1.client-secret=s3cr3t")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("Set tm1.user"));

		runner
			.withPropertyValues("tm1.base-url=http://tm1:4444/tm1/api/v1/Databases('tm1')/", "tm1.user=admin",
					"tm1.client-id=app-1", "tm1.client-secret=s3cr3t")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("Set tm1.instance and tm1.database"));

		runner
			.withPropertyValues("tm1.address=http://tm1:4444", "tm1.instance=tm1", "tm1.database=planning",
					"tm1.bearer-token=tok", "tm1.client-id=app-1", "tm1.client-secret=s3cr3t")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("tm1.bearer-token"));

		runner
			.withPropertyValues("tm1.address=http://tm1:4444", "tm1.instance=tm1", "tm1.database=planning",
					"tm1.bearer-token=tok", "tm1.namespace=LDAP")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("tm1.bearer-token"));

		runner
			.withPropertyValues("tm1.address=http://tm1:4444", "tm1.instance=tm1", "tm1.database=planning",
					"tm1.bearer-token=tok", "tm1.user=admin")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("tm1.bearer-token"));

		runner
			.withPropertyValues("tm1.address=http://tm1:4444", "tm1.instance=tm1", "tm1.database=planning",
					"tm1.user=admin", "tm1.password=pw", "tm1.client-id=app-1", "tm1.client-secret=s3cr3t")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("tm1.password"));

		runner
			.withPropertyValues("tm1.address=http://tm1:4444", "tm1.instance=tm1", "tm1.database=planning",
					"tm1.user=admin", "tm1.client-id=app-1", "tm1.client-secret=s3cr3t", "tm1.namespace=LDAP")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("tm1.namespace"));
	}

	@Test
	void treatsBlankOptionalPropertiesAsUnset() {
		runner
			.withPropertyValues("tm1.address=http://tm1:8010", "tm1.user=admin", "tm1.password=", "tm1.instance=",
					"tm1.database=", "tm1.bearer-token=", "tm1.client-id=", "tm1.client-secret=")
			.run(context -> {
				Tm1ConnectionDetails details = context.getBean(Tm1ConnectionDetails.class);
				assertThat(details.getDialect()).isEqualTo(Tm1Dialect.V11);
				assertThat(details.getServiceRoot()).isEqualTo("http://tm1:8010/api/v1/");
				assertThat(details.getAuthenticator().describe()).isEqualTo("Basic admin");
			});
	}

	@Test
	void explainsAMissingUser() {
		runner.withPropertyValues("tm1.address=http://tm1:8010")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("Set tm1.user"));
	}

	@Test
	void explainsAnUnknownSslBundle() {
		runner.withPropertyValues("tm1.address=https://tm1:8010", "tm1.user=admin", "tm1.ssl.bundle=nope")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void usesConnectionDetailsDefinedElsewhere() {
		runner.withBean(Tm1ConnectionDetails.class, () -> new Tm1ConnectionDetails() {

			@Override
			public String getServiceRoot() {
				return "http://elsewhere:9000/api/v1/";
			}

			@Override
			public Tm1Authenticator getAuthenticator() {
				return Tm1Authenticator.basic("svc", "x");
			}
		}).run(context -> {
			assertThat(context).doesNotHaveBean(PropertiesTm1ConnectionDetails.class);
			assertThat(context.getBean(Tm1ConnectionDetails.class).getDialect()).isEqualTo(Tm1Dialect.V11);
			assertThat(context.getBean(Tm1Session.class).serviceRoot()).isEqualTo("http://elsewhere:9000/api/v1/");
		});
	}

	@Test
	void backsOffForASessionOfYourOwn() {
		Tm1Session mine = Tm1Session.builder().baseUrl("http://mine:1/api/v1/").basicAuth("me", "").build();
		runner.withPropertyValues("tm1.address=http://tm1:8010", "tm1.user=admin")
			.withBean(Tm1Session.class, () -> mine)
			.run(context -> {
				assertThat(context.getBean(Tm1Session.class)).isSameAs(mine);
				assertThat(context.getBean(Tm1Client.class).session()).isSameAs(mine);
			});
	}

	@Test
	void createsThePoolWhenEnabled() {
		runner
			.withPropertyValues("tm1.address=http://tm1:8010", "tm1.user=admin", "tm1.pool.enabled=true",
					"tm1.pool.max-size=5")
			.run(context -> assertThat(context).hasSingleBean(Tm1SessionPool.class));
	}

	@Test
	void closesTheSessionAndThePoolOnShutdown() {
		Tm1Session[] session = new Tm1Session[1];
		Tm1SessionPool[] pool = new Tm1SessionPool[1];
		runner.withPropertyValues("tm1.address=http://tm1:8010", "tm1.user=admin", "tm1.pool.enabled=true")
			.run(context -> {
				session[0] = context.getBean(Tm1Session.class);
				pool[0] = context.getBean(Tm1SessionPool.class);
			});
		assertThat(session[0].isClosed()).isTrue();
		assertThatThrownBy(() -> pool[0].session()).isInstanceOf(Tm1SessionClosedException.class);
	}

}

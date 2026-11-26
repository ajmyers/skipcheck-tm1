// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.ServerAssumptions;
import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1BadRequestException;
import io.skipcheck.tm1.client.model.DimensionCreate;
import io.skipcheck.tm1.client.model.ElementCreate;
import io.skipcheck.tm1.client.model.Git;
import io.skipcheck.tm1.client.model.GitPullPlan;
import io.skipcheck.tm1.client.model.GitPushPlan;
import io.skipcheck.tm1.client.model.HierarchyCreate;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/** GitService: a model pushed to a Git remote and pulled back. */
@RequiresTm1
class GitServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private static final GitCommitDetails COMMIT = new GitCommitDetails("Add a dimension", "tm1-client",
			"tm1-client@example.com");

	private static Tm1ServerContainer server;

	private static Tm1Client tm1;

	@BeforeAll
	static void connect(Tm1ServerContainer container) {
		server = container;
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
	}

	@AfterAll
	static void close() {
		tm1.close().block(WAIT);
	}

	@Test
	void pushesTheModelAndPullsItBack() throws Exception {
		String dimension = "git" + UUID.randomUUID().toString().substring(0, 8);
		String copy = dimension + "copy";
		try (GitDaemon remote = new GitDaemon(server)) {
			remote.start();
			assertThatThrownBy(() -> tm1.git().status(GitCredentials.none()).block(WAIT)).as("not initialized yet")
				.isInstanceOf(Tm1BadRequestException.class);
			Git tied = tm1.git().init(GitDaemon.URL, "dev", GitCredentials.none(), true, null).block(WAIT);
			try {
				assertThat(tied.remote().branches()).containsExactly("main");
				assertThatThrownBy(() -> tm1.git().push("main", null, COMMIT, GitCredentials.none(), true).block(WAIT))
					.as("nothing has changed")
					.hasMessageContaining("Cannot create bare commit");

				tm1.dimensions()
					.create(DimensionCreate.builder()
						.name(dimension)
						.hierarchies(List.of(HierarchyCreate.builder()
							.name(dimension)
							.elements(List.of(ElementCreate.builder().name("North").build()))
							.build()))
						.build())
					.block(WAIT);
				GitPushPlan push = tm1.git().push("main", null, COMMIT, GitCredentials.none(), true).block(WAIT);
				assertThat(push.sourceFiles()).contains("/dimensions/" + dimension + ".json");
				assertThat(tm1.git().plan(push.id()).block(WAIT)).isInstanceOf(GitPushPlan.class);
				tm1.git().execute(push).block(WAIT);
				assertThat(tm1.git().plan(push.id()).blockOptional(WAIT)).as("executing a plan uses it up").isEmpty();
				assertThat(remote.filesOnMain()).contains("dimensions/" + dimension + ".json");
				assertThat(tm1.git().status(GitCredentials.none()).block(WAIT).deployedCommit().summary())
					.isEqualTo("Add a dimension");

				tm1.dimensions().delete(dimension).block(WAIT);
				GitPullPlan skip = tm1.git().pull("main", GitCredentials.none(), null, true).block(WAIT);
				assertThat(skip.operations()).as("a pull compares commits, not the model: the local delete stands")
					.containsExactly("Skip Dimensions('" + dimension + "')");
				tm1.git().execute(skip).block(WAIT);

				remote.commitCopyOf(dimension, copy);
				// The first pull after the remote moves: from 11.8.034 the commit TM1
				// had, then the new one; before, the new one at once
				GitPullPlan pull = tm1.git().pull("main", GitCredentials.none(), null, true).block(WAIT);
				if (ServerAssumptions.isAtLeast(tm1, ServerAssumptions.STALE_FIRST_PULL_FROM)) {
					assertThat(pull.commit().summary()).as("the commit TM1 had").isEqualTo("Add a dimension");
					pull = tm1.git().pull("main", GitCredentials.none(), null, true).block(WAIT);
				}
				assertThat(pull.commit().summary()).isEqualTo("Copy " + dimension);
				assertThat(pull.operations()).contains("Create Dimensions('" + copy + "')");
				tm1.git().execute(pull).block(WAIT);
				assertThat(tm1.dimensions().get(copy, ODataQuery.none()).block(WAIT).name()).isEqualTo(copy);

				assertThatThrownBy(() -> tm1.git()
					.deploy(GitDaemon.URL, "main", "prod", GitCredentials.none(), true, null)
					.block(WAIT)).as("refused while initialized").hasMessageContaining("Cannot re-deploy");
			}
			finally {
				tm1.git().uninit(true).block(WAIT);
				tm1.dimensions().delete(dimension).onErrorComplete().block(WAIT);
				tm1.dimensions().delete(copy).onErrorComplete().block(WAIT);
			}
			tm1.git().deploy(GitDaemon.URL, "main", "dev", GitCredentials.none(), true, null).block(WAIT);
			try {
				assertThat(tm1.git().status(GitCredentials.none()).block(WAIT).deployedCommit().summary())
					.isEqualTo("Copy " + dimension);
				assertThat(tm1.dimensions().get(dimension, ODataQuery.none()).block(WAIT).name())
					.as("a deploy brings back the whole branch, the deleted dimension too")
					.isEqualTo(dimension);
			}
			finally {
				tm1.git().uninit(true).block(WAIT);
				tm1.dimensions().delete(dimension).onErrorComplete().block(WAIT);
				tm1.dimensions().delete(copy).onErrorComplete().block(WAIT);
			}
		}
	}

	@Test
	void keepsPasswordsOutOfToString() {
		assertThat(GitCredentials.password("me", "s3cret"))
			.hasToString("GitCredentials[username=me, password=***, sshKey=none]");
	}

}

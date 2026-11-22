package net.ajmyers.tm1.client.service.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import net.ajmyers.tm1.client.conformance.Conformance;
import net.ajmyers.tm1.client.conformance.ConformanceCodec;
import net.ajmyers.tm1.client.conformance.ConformanceTm1;
import net.ajmyers.tm1.client.error.Tm1BadRequestException;
import net.ajmyers.tm1.client.error.Tm1NotFoundException;
import net.ajmyers.tm1.client.model.Git;
import net.ajmyers.tm1.client.model.GitConfigurationPatch;
import net.ajmyers.tm1.client.model.GitPlan;
import net.ajmyers.tm1.client.model.GitPlanExecutionMode;
import net.ajmyers.tm1.client.model.GitPullPlan;
import net.ajmyers.tm1.client.model.GitPushPlan;
import net.ajmyers.tm1.client.odata.ODataQuery;
import tools.jackson.databind.JsonNode;

/**
 * GitService against a fake TM1: what each method sends, and how it reads TM1's answer.
 * Every call is recorded to {@code spec/conformance/calls/GitServiceTest.json}, which the
 * Python, .NET and TypeScript clients replay.
 */
class GitServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	static {
		ConformanceCodec.register(GitCredentials.class, GitServiceTest::credentials);
		ConformanceCodec.register(GitCommitDetails.class, value -> {
			GitCommitDetails commit = (GitCommitDetails) value;
			return ConformanceCodec.factory("GitCommitDetails", "new", commit.message(), commit.author(),
					commit.email());
		});
	}

	private static final String URL = "git://git.example.com/model.git";

	private static final GitCommitDetails COMMIT = new GitCommitDetails("Add the Region dimension", "Ada Analyst",
			"ada@example.com");

	private static final String GIT = "{\"URL\":\"git://git.example.com/model.git\",\"Deployment\":\"dev\","
			+ "\"DeployedCommit\":{\"ID\":\"c1\",\"Summary\":\"First\",\"Author\":\"Ada\"},"
			+ "\"Remote\":{\"Connected\":true,\"Branches\":[\"main\"],\"Tags\":[]}}";

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	/** Credentials as the factory that makes them. */
	static JsonNode credentials(Object value) {
		GitCredentials c = (GitCredentials) value;
		if (c.equals(GitCredentials.none())) {
			return ConformanceCodec.factory("GitCredentials", "none");
		}
		if (c.equals(GitCredentials.password(String.valueOf(c.username()), String.valueOf(c.password())))) {
			return ConformanceCodec.factory("GitCredentials", "password", c.username(), c.password());
		}
		if (c.equals(
				GitCredentials.sshKey(String.valueOf(c.publicKey()), String.valueOf(c.privateKey()), c.passphrase()))) {
			return ConformanceCodec.factory("GitCredentials", "sshKey", c.publicKey(), c.privateKey(), c.passphrase());
		}
		throw new IllegalArgumentException("No factory makes " + c);
	}

	// Plain Java, nothing sent

	@Test
	void keepsTheSecretsOutOfToString() {
		assertThat(GitCredentials.password("me", "s3cret"))
			.hasToString("GitCredentials[username=me, password=***, sshKey=none]");
		assertThat(GitCredentials.sshKey("pub", "priv", "phrase"))
			.hasToString("GitCredentials[username=null, password=none, sshKey=***]");
		assertThat(GitCredentials.none()).hasToString("GitCredentials[username=null, password=none, sshKey=none]")
			.isEqualTo(new GitCredentials(null, null, null, null, null));
	}

	@Test
	void aCommitNeedsEachPart() {
		assertThatThrownBy(() -> new GitCommitDetails(null, "a", "a@example.com"))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new GitCommitDetails("m", null, "a@example.com"))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new GitCommitDetails("m", "a", null)).isInstanceOf(NullPointerException.class);
	}

	// Recorded calls

	@Test
	void initsWithEachKindOfCredentials() {
		tm1().json(GIT);
		Git git = conformance.call("git", "init", URL, "dev", GitCredentials.password("me", "s3cret"), true, null);
		assertThat(git.remote().branches()).containsExactly("main");
		assertThat(git.deployedCommit().id()).isEqualTo("c1");
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST GitInit");
		assertThat(post.text()).isEqualTo("{\"URL\":\"git://git.example.com/model.git\",\"Username\":\"me\","
				+ "\"Password\":\"s3cret\",\"Deployment\":\"dev\",\"Force\":true}");

		tm1().json(GIT);
		conformance.call("git", "init", URL, null, GitCredentials.none(), false,
				GitConfigurationPatch.builder().build());
		assertThat(tm1().last().text())
			.isEqualTo("{\"URL\":\"git://git.example.com/model.git\",\"Force\":false,\"Config\":{}}");

		tm1().json(GIT);
		conformance.call("git", "init", URL, "prod", GitCredentials.sshKey("pub", "priv", "phrase"), false,
				GitConfigurationPatch.builder().property("core.autocrlf", "false").build());
		assertThat(tm1().last().text())
			.isEqualTo("{\"URL\":\"git://git.example.com/model.git\",\"PublicKey\":\"pub\",\"PrivateKey\":\"priv\","
					+ "\"Passphrase\":\"phrase\",\"Deployment\":\"prod\",\"Force\":false,\"Config\":{\"core.autocrlf\":\"false\"}}");
	}

	@Test
	void anUnreachableRemoteIsABadRequest() {
		tm1().error(400, "1", "Failed to connect to the remote repository");
		Throwable error = conformance.fail("git", "init", "https://nowhere.example.com/x.git", null,
				GitCredentials.none(), false, null);
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void uninits() {
		tm1().status(204);
		Object none = conformance.call("git", "uninit", true);
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("POST GitUninit");
		assertThat(tm1().last().text()).isEqualTo("{\"Force\":true}");

		tm1().status(204);
		conformance.call("git", "uninit", false);
		assertThat(tm1().last().text()).isEqualTo("{\"Force\":false}");
	}

	@Test
	void readsTheStatus() {
		tm1().json(GIT);
		Git git = conformance.call("git", "status", GitCredentials.none());
		assertThat(git.url()).isEqualTo(URL);
		assertThat(tm1().last().key()).isEqualTo("POST GitStatus");
		assertThat(tm1().last().text()).isEqualTo("{}");

		tm1().json(GIT);
		conformance.call("git", "status", GitCredentials.sshKey("pub", "priv", null));
		assertThat(tm1().last().text()).isEqualTo("{\"PublicKey\":\"pub\",\"PrivateKey\":\"priv\"}");
	}

	@Test
	void theStatusOfAServerNotTiedIsABadRequest() {
		tm1().error(400, "1", "Git is not initialized");
		Throwable error = conformance.fail("git", "status", GitCredentials.none());
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void plansAPush() {
		// As TM1 answers an action: no @odata.type for its declared return type
		tm1().json("{\"ID\":\"p1\",\"Branch\":\"main\",\"NewBranch\":\"feature\",\"Force\":false,"
				+ "\"SourceFiles\":[\"/dimensions/Region.json\"],\"ParentCommit\":{\"ID\":\"c1\"}}");
		GitPushPlan plan = conformance.call("git", "push", "main", "feature", COMMIT,
				GitCredentials.password("me", "s3cret"), false);
		assertThat(plan.sourceFiles()).containsExactly("/dimensions/Region.json");
		ConformanceTm1.Recorded post = tm1().last();
		assertThat(post.key()).isEqualTo("POST GitPush");
		assertThat(post.text()).isEqualTo("{\"Username\":\"me\",\"Password\":\"s3cret\",\"Branch\":\"main\","
				+ "\"NewBranch\":\"feature\",\"Message\":\"Add the Region dimension\",\"Author\":\"Ada Analyst\","
				+ "\"Email\":\"ada@example.com\",\"Force\":false}");

		tm1().json("{\"ID\":\"p2\",\"Branch\":\"main\"}");
		conformance.call("git", "push", "main", null, COMMIT, GitCredentials.none(), true);
		assertThat(tm1().last().text()).isEqualTo("{\"Branch\":\"main\",\"Message\":\"Add the Region dimension\","
				+ "\"Author\":\"Ada Analyst\",\"Email\":\"ada@example.com\",\"Force\":true}");
	}

	@Test
	void pushingNothingNewIsABadRequest() {
		tm1().error(400, "1", "Cannot create bare commit");
		Throwable error = conformance.fail("git", "push", "main", null, COMMIT, GitCredentials.none(), false);
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void plansAPull() {
		tm1().json("{\"ID\":\"p3\",\"Branch\":\"main\",\"Commit\":{\"ID\":\"c2\"},"
				+ "\"Operations\":[\"Skip Dimensions('Region')\"],\"ExecutionMode\":\"MultipleCommit\"}");
		GitPullPlan plan = conformance.call("git", "pull", "main", GitCredentials.none(),
				GitPlanExecutionMode.MULTIPLE_COMMIT, true);
		assertThat(plan.operations()).containsExactly("Skip Dimensions('Region')");
		assertThat(plan.executionMode()).isEqualTo(GitPlanExecutionMode.MULTIPLE_COMMIT);
		assertThat(tm1().last().key()).isEqualTo("POST GitPull");
		assertThat(tm1().last().text())
			.isEqualTo("{\"Branch\":\"main\",\"ExecutionMode\":\"MultipleCommit\",\"Force\":true}");

		tm1().json("{\"ID\":\"p4\"}");
		conformance.call("git", "pull", "release/1.0", GitCredentials.password("me", "t0ken"), null, false);
		assertThat(tm1().last().text())
			.isEqualTo("{\"Username\":\"me\",\"Password\":\"t0ken\",\"Branch\":\"release/1.0\",\"Force\":false}");
	}

	@Test
	void deploys() {
		tm1().status(204);
		Object none = conformance.call("git", "deploy", URL, "main", "prod", GitCredentials.password("me", "s3cret"),
				true, GitConfigurationPatch.builder().property("core.autocrlf", "false").build());
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("POST GitDeploy");
		assertThat(tm1().last().text()).isEqualTo("{\"URL\":\"git://git.example.com/model.git\",\"Username\":\"me\","
				+ "\"Password\":\"s3cret\",\"Branch\":\"main\",\"Deployment\":\"prod\",\"Force\":true,"
				+ "\"Config\":{\"core.autocrlf\":\"false\"}}");

		tm1().status(204);
		conformance.call("git", "deploy", URL, "main", null, GitCredentials.none(), false, null);
		assertThat(tm1().last().text())
			.isEqualTo("{\"URL\":\"git://git.example.com/model.git\",\"Branch\":\"main\",\"Force\":false}");
	}

	@Test
	void redeployingIsABadRequest() {
		tm1().error(400, "1", "Cannot re-deploy");
		Throwable error = conformance.fail("git", "deploy", URL, "main", null, GitCredentials.none(), false, null);
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void listsThePlansOfEachKind() {
		tm1().json("{\"value\":[{\"@odata.type\":\"#ibm.tm1.api.v1.GitPushPlan\",\"ID\":\"p1\"},"
				+ "{\"@odata.type\":\"#ibm.tm1.api.v1.GitPullPlan\",\"ID\":\"p2\"}]}");
		List<GitPlan> plans = conformance.call("git", "plans", ODataQuery.none().select("ID"));
		assertThat(plans).hasExactlyElementsOfTypes(GitPushPlan.class, GitPullPlan.class);
		assertThat(tm1().last().key()).isEqualTo("GET GitPlans?$select=ID");

		tm1().json("{\"value\":[]}");
		List<GitPlan> none = conformance.call("git", "plans", ODataQuery.none());
		assertThat(none).isEmpty();
	}

	@Test
	void findsAPlanInTheList() {
		tm1().json("{\"value\":[{\"@odata.type\":\"#ibm.tm1.api.v1.GitPushPlan\",\"ID\":\"p1\"},"
				+ "{\"@odata.type\":\"#ibm.tm1.api.v1.GitPullPlan\",\"ID\":\"p2\"}]}");
		GitPlan plan = conformance.call("git", "plan", "p2");
		assertThat(plan).isInstanceOf(GitPullPlan.class);
		assertThat(tm1().last().key()).isEqualTo("GET GitPlans");

		tm1().json("{\"value\":[{\"@odata.type\":\"#ibm.tm1.api.v1.GitPushPlan\",\"ID\":\"p1\"}]}");
		GitPlan gone = conformance.call("git", "plan", "gone");
		assertThat(gone).as("an executed plan is gone").isNull();
	}

	@Test
	void thePlansOfAServerNotTiedAreABadRequest() {
		tm1().error(400, "1", "Git is not initialized");
		Throwable error = conformance.fail("git", "plans", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1BadRequestException.class);
	}

	@Test
	void executesAPushOrAPull() {
		tm1().status(204);
		Object none = conformance.call("git", "execute",
				new GitPushPlan(null, "p1", "main", false, null, null, null, List.of("/dimensions/Region.json")));
		assertThat(none).isNull();
		assertThat(tm1().last().key()).isEqualTo("POST GitPlans('p1')/tm1.GitPushPlan/tm1.Execute");

		tm1().status(204);
		conformance.call("git", "execute", new GitPullPlan(null, "it's", null, null, null, null, null));
		assertThat(tm1().last().key()).isEqualTo("POST GitPlans('it''s')/tm1.GitPullPlan/tm1.Execute");
	}

	@Test
	void anExecutedPlanIsGone() {
		tm1().error(404, "278", "'p1' can not be found in collection of type 'GitPlan'.");
		Throwable error = conformance.fail("git", "execute",
				new GitPushPlan(null, "p1", null, null, null, null, null, null));
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void aPlanWithoutAnIdCantBeExecuted() {
		Throwable error = conformance.fail("git", "execute",
				new GitPushPlan(null, null, "main", null, null, null, null, null));
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

	@Test
	void aPlanOfAnUnknownKindCantBeExecuted() {
		// Not recorded: no other client has Java's GitPlan.Unknown to pass
		GitPlan unknown = new GitPlan.Unknown(null, "p9", null, null);
		assertThatThrownBy(() -> conformance.client().git().execute(unknown).block())
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}

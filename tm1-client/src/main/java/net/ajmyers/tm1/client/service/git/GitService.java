package net.ajmyers.tm1.client.service.git;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import net.ajmyers.tm1.client.Tm1Operation;
import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.Git;
import net.ajmyers.tm1.client.model.GitConfigurationPatch;
import net.ajmyers.tm1.client.model.GitPlan;
import net.ajmyers.tm1.client.model.GitPlanExecutionMode;
import net.ajmyers.tm1.client.model.GitPullPlan;
import net.ajmyers.tm1.client.model.GitPushPlan;
import net.ajmyers.tm1.client.model.Tm1Operations;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * TM1 Git (schema 11.5.0): ties the model to a Git repository, pushes its objects there
 * as JSON files ({@code dimensions/Region.json}, …), and pulls them back, on this server
 * or another.
 *
 * <p>
 * Pushing and pulling are two steps: {@link #push} and {@link #pull} make a plan saying
 * what would happen (the files, or the operations, such as
 * {@code Skip Dimensions('Region')}), and {@link #execute} carries it out.
 *
 * <p>
 * What TM1 (11.8) does here that you might not expect:
 * <ul>
 * <li>A plan belongs to the TM1 session that made it: another session can't see it or run
 * it. Executing a plan uses it up; it's gone afterwards.</li>
 * <li>The remote needs a commit on the branch to start from: TM1 can't make a
 * repository's first commit, and pushing when the model hasn't changed is refused too
 * (both "Cannot create bare commit").</li>
 * <li>A local {@code file://} remote works for {@link #init} and {@link #status}, but not
 * for pushing or pulling ("shallow fetch is not supported by the local transport"); use
 * {@code https://}, {@code ssh://} or {@code git://}. TM1 also refuses a local repository
 * its own user doesn't own.</li>
 * <li>{@link #deploy} is refused while Git is initialized ("Cannot re-deploy"): it's for
 * a server not yet tied to the repository.</li>
 * <li>A pull compares commits, not the model: it plans the changes between the commit
 * last deployed and the branch, so an object deleted on the server since is skipped
 * ({@code Skip Dimensions('Region')}), not brought back. {@link #deploy} brings the whole
 * branch.</li>
 * <li>The first pull after the remote moves on still plans the commit TM1 had; the next
 * pull plans the new one. Check the plan's {@code Commit} before executing it.</li>
 * </ul>
 */
public final class GitService {

	private static final ODataPath PLANS = ODataPath.of("GitPlans");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public GitService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GitInit}: ties the model to a repository.
	 * @param url the remote's URL
	 * @param deployment the deployment's name, such as {@code dev}, or {@code null} for
	 * none
	 * @param credentials how to sign in to the remote
	 * @param force whether to replace an existing tie
	 * @param config the Git configuration, or {@code null} for TM1's
	 * @return the tie: URL, deployment, deployed commit, and the remote's branches and
	 * tags
	 */
	@Tm1Endpoint("ActionImport GitInit")
	public Mono<Git> init(String url, @Nullable String deployment, GitCredentials credentials, boolean force,
			@Nullable GitConfigurationPatch config) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("URL", Objects.requireNonNull(url, "url"));
		credentials.addTo(parameters);
		GitCredentials.put(parameters, "Deployment", deployment);
		parameters.put("Force", force);
		GitCredentials.put(parameters, "Config", config);
		return call(Tm1Operations.GIT_INIT, parameters, Git.class);
	}

	/**
	 * {@code GitUninit}: unties the model from its repository. The model is left as it
	 * is.
	 * @param force whether to untie it even with a plan waiting
	 * @return completes when it's untied
	 */
	@Tm1Endpoint("ActionImport GitUninit")
	public Mono<Void> uninit(boolean force) {
		return call(Tm1Operations.GIT_UNINIT, Map.of("Force", force), Void.class);
	}

	/**
	 * {@code GitStatus}: the tie, and what's on the remote. Fails with a
	 * {@code Tm1BadRequestException} if Git isn't initialized.
	 * @param credentials how to sign in to the remote
	 * @return the tie
	 */
	@Tm1Endpoint("ActionImport GitStatus")
	public Mono<Git> status(GitCredentials credentials) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		credentials.addTo(parameters);
		return call(Tm1Operations.GIT_STATUS, parameters, Git.class);
	}

	/**
	 * {@code GitPush}: plans a commit of the model's changes on top of {@code branch}.
	 * The plan lists the files; {@link #execute} pushes them.
	 * @param branch the branch to start from, which must exist on the remote
	 * @param newBranch the branch to push to, or {@code null} for {@code branch}
	 * @param commit the commit's message and author
	 * @param credentials how to sign in to the remote
	 * @param force whether to push even if the remote moved on
	 * @return the plan
	 */
	@Tm1Endpoint("ActionImport GitPush")
	public Mono<GitPushPlan> push(String branch, @Nullable String newBranch, GitCommitDetails commit,
			GitCredentials credentials, boolean force) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		credentials.addTo(parameters);
		parameters.put("Branch", Objects.requireNonNull(branch, "branch"));
		GitCredentials.put(parameters, "NewBranch", newBranch);
		parameters.put("Message", commit.message());
		parameters.put("Author", commit.author());
		parameters.put("Email", commit.email());
		parameters.put("Force", force);
		return call(Tm1Operations.GIT_PUSH, parameters, GitPushPlan.class);
	}

	/**
	 * {@code GitPull}: plans bringing the model up to {@code branch}. Pull again if the
	 * plan's {@code Commit} isn't the branch's newest: TM1 (11.8) lags a pull behind the
	 * remote. The plan lists the operations; {@link #execute} carries them out.
	 * @param branch the branch to pull
	 * @param credentials how to sign in to the remote
	 * @param mode one commit or each commit in turn, or {@code null} for TM1's
	 * ({@code SingleCommit})
	 * @param force whether to pull over local changes
	 * @return the plan
	 */
	@Tm1Endpoint("ActionImport GitPull")
	public Mono<GitPullPlan> pull(String branch, GitCredentials credentials, @Nullable GitPlanExecutionMode mode,
			boolean force) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		credentials.addTo(parameters);
		parameters.put("Branch", Objects.requireNonNull(branch, "branch"));
		GitCredentials.put(parameters, "ExecutionMode", mode == null ? null : mode.json());
		parameters.put("Force", force);
		return call(Tm1Operations.GIT_PULL, parameters, GitPullPlan.class);
	}

	/**
	 * {@code GitDeploy}: ties a server not yet tied to a repository, and pulls
	 * {@code branch} into it, in one step. Refused while Git is initialized.
	 * @param url the remote's URL
	 * @param branch the branch to deploy
	 * @param deployment the deployment's name, or {@code null} for none
	 * @param credentials how to sign in to the remote
	 * @param force whether to deploy over local changes
	 * @param config the Git configuration, or {@code null} for TM1's
	 * @return completes when it's deployed; {@link #status} then shows the commit
	 */
	@Tm1Endpoint("ActionImport GitDeploy")
	public Mono<Void> deploy(String url, String branch, @Nullable String deployment, GitCredentials credentials,
			boolean force, @Nullable GitConfigurationPatch config) {
		Map<String, Object> parameters = new LinkedHashMap<>();
		parameters.put("URL", Objects.requireNonNull(url, "url"));
		credentials.addTo(parameters);
		parameters.put("Branch", Objects.requireNonNull(branch, "branch"));
		GitCredentials.put(parameters, "Deployment", deployment);
		parameters.put("Force", force);
		GitCredentials.put(parameters, "Config", config);
		return call(Tm1Operations.GIT_DEPLOY, parameters, Void.class);
	}

	/**
	 * {@code GET GitPlans}, streamed: this session's plans not yet executed. Fails with a
	 * {@code Tm1BadRequestException} if Git isn't initialized.
	 * @param query what to select and filter
	 * @return the plans, each a {@code GitPushPlan} or {@code GitPullPlan}
	 */
	@Tm1Endpoint("EntitySet GitPlans")
	public Flux<GitPlan> plans(ODataQuery query) {
		return session.stream(Tm1Request.list(PLANS, query, GitPlan.class));
	}

	/**
	 * One of this session's plans, by ID. TM1 (11.8) sends no {@code @odata.type} for
	 * {@code GitPlans('id')}, so it couldn't be told apart as a push or a pull; the list
	 * says, so the plan is found there.
	 * @param id the plan's ID
	 * @return the plan, or empty if this session has none by that ID (an executed plan is
	 * gone)
	 */
	@Tm1Endpoint("EntitySet GitPlans")
	public Mono<GitPlan> plan(String id) {
		Objects.requireNonNull(id, "id");
		return plans(ODataQuery.none()).filter(plan -> id.equals(plan.id())).next();
	}

	/**
	 * {@code tm1.Execute}: carries out a plan, pushing its files or pulling its
	 * operations. The plan is used up.
	 * @param plan the plan, from {@link #push}, {@link #pull} or {@link #plans}
	 * @return completes when it's done
	 */
	@Tm1Endpoint("Action GitPushPlan/Execute")
	@Tm1Endpoint("Action GitPullPlan/Execute")
	public Mono<Void> execute(GitPlan plan) {
		String id = Objects.requireNonNull(plan.id(), "the plan's ID");
		Tm1Operation operation;
		String cast;
		if (plan instanceof GitPushPlan) {
			operation = Tm1Operations.GIT_PUSH_PLAN_EXECUTE;
			cast = "tm1.GitPushPlan";
		}
		else if (plan instanceof GitPullPlan) {
			operation = Tm1Operations.GIT_PULL_PLAN_EXECUTE;
			cast = "tm1.GitPullPlan";
		}
		else {
			return Mono.error(new IllegalArgumentException("Not a push or pull plan: " + plan));
		}
		return session.execute(Tm1Request.post(PLANS.key(id).cast(cast).action(operation.name()), null, Void.class)
			.requires(operation.capability()));
	}

	private <T> Mono<T> call(Tm1Operation operation, Map<String, ?> parameters, Class<T> type) {
		return session.execute(Tm1Request
			.post(ODataPath.of(Objects.requireNonNull(operation.importName(), operation.name())), parameters, type)
			.requires(operation.capability()));
	}

}

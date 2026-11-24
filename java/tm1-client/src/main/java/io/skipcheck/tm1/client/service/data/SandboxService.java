// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.ConflictResolution;
import io.skipcheck.tm1.client.model.Sandbox;
import io.skipcheck.tm1.client.model.SandboxCreate;
import io.skipcheck.tm1.client.model.SandboxPatch;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Sandboxes: private copies of the data a user writes to before publishing. They belong
 * to the user the session is for.
 *
 * <p>
 * Reading and writing in a sandbox go through a session view:
 * {@code Tm1Session.withSandbox} or {@code Tm1Client.withSandbox}, which add TM1's
 * {@code !sandbox} option to every request. This service manages the sandboxes
 * themselves.
 *
 * <p>
 * Create a sandbox, write to it, and publish the changes to base data:
 * {@snippet class = examples.SandboxServiceExamples region = publish}
 *
 * <p>
 * Read a cell in base data and in a sandbox:
 * {@snippet class = examples.SandboxServiceExamples region = compare}
 */
public final class SandboxService {

	private static final ODataPath SANDBOXES = ODataPath.of("Sandboxes");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public SandboxService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Sandboxes}, streamed: the session user's sandboxes.
	 * @param query what to select, filter and order
	 * @return the sandboxes
	 */
	@Tm1Endpoint("EntitySet Sandboxes")
	public Flux<Sandbox> list(ODataQuery query) {
		return session.stream(Tm1Request.list(SANDBOXES, query, Sandbox.class));
	}

	/**
	 * {@code GET Sandboxes('name')}.
	 * @param name the sandbox's name
	 * @param query what to select
	 * @return the sandbox, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet Sandboxes")
	public Mono<Sandbox> get(String name, ODataQuery query) {
		return session.execute(Tm1Request.get(sandbox(name), query, Sandbox.class));
	}

	/**
	 * {@code POST Sandboxes}: creates an empty sandbox.
	 * @param sandbox the new sandbox; it needs a name
	 * @return the sandbox as created
	 */
	@Tm1Endpoint("EntitySet Sandboxes")
	public Mono<Sandbox> create(SandboxCreate sandbox) {
		return session.execute(Tm1Request.post(SANDBOXES, sandbox, Sandbox.class));
	}

	/**
	 * {@code PATCH Sandboxes('name')}: changes the properties the patch sets, such as
	 * {@code IncludeInSandboxDimension}.
	 * @param name the sandbox's name
	 * @param patch the changes
	 * @return completes when the sandbox is changed
	 */
	@Tm1Endpoint("EntitySet Sandboxes")
	public Mono<Void> update(String name, SandboxPatch patch) {
		return session.execute(Tm1Request.patch(sandbox(name), patch));
	}

	/**
	 * {@code DELETE Sandboxes('name')}, with its unpublished changes.
	 * @param name the sandbox's name
	 * @return completes when the sandbox is deleted
	 */
	@Tm1Endpoint("EntitySet Sandboxes")
	public Mono<Void> delete(String name) {
		return session.execute(Tm1Request.delete(sandbox(name)));
	}

	/**
	 * {@code tm1.Load}: loads the sandbox into memory.
	 * @param name the sandbox's name
	 * @return completes when it's loaded
	 */
	@Tm1Endpoint("Action Sandbox/Load")
	public Mono<Void> load(String name) {
		return session.execute(action(Tm1Operations.SANDBOX_LOAD, name, Map.of()));
	}

	/**
	 * {@code tm1.Unload}: frees the sandbox's memory, keeping its changes.
	 * @param name the sandbox's name
	 * @return completes when it's unloaded
	 */
	@Tm1Endpoint("Action Sandbox/Unload")
	public Mono<Void> unload(String name) {
		return session.execute(action(Tm1Operations.SANDBOX_UNLOAD, name, Map.of()));
	}

	/**
	 * {@code tm1.DiscardChanges}: throws away the sandbox's changes, keeping the sandbox.
	 * @param name the sandbox's name
	 * @return completes when they're discarded
	 */
	@Tm1Endpoint("Action Sandbox/DiscardChanges")
	public Mono<Void> discardChanges(String name) {
		return session.execute(action(Tm1Operations.SANDBOX_DISCARD_CHANGES, name, Map.of()));
	}

	/**
	 * {@code tm1.Publish}: writes the sandbox's changes to base data.
	 * @param name the sandbox's name
	 * @return completes when they're published
	 */
	@Tm1Endpoint("Action Sandbox/Publish")
	public Mono<Void> publish(String name) {
		return session.execute(action(Tm1Operations.SANDBOX_PUBLISH, name, Map.of()));
	}

	/**
	 * {@code tm1.Publish} with options.
	 * @param name the sandbox's name
	 * @param conflicts what to do with cells changed in base data since the sandbox's
	 * changes
	 * @param allowQueueing whether to wait for a lock rather than fail
	 * @param cleanAfter whether to empty the sandbox once published
	 * @return completes when they're published
	 */
	@Tm1Endpoint("Action Sandbox/Publish")
	public Mono<Void> publish(String name, ConflictResolution conflicts, boolean allowQueueing, boolean cleanAfter) {
		return session
			.execute(action(Tm1Operations.SANDBOX_PUBLISH, name, options(conflicts, allowQueueing, cleanAfter)));
	}

	/**
	 * {@code tm1.Merge}: copies the sandbox's changes into another sandbox.
	 * @param name the sandbox's name
	 * @param target the name of the sandbox to merge into
	 * @return completes when they're merged
	 */
	@Tm1Endpoint("Action Sandbox/Merge")
	public Mono<Void> merge(String name, String target) {
		return session.execute(
				action(Tm1Operations.SANDBOX_MERGE, name, Map.of("Target@odata.bind", sandbox(target).toString())));
	}

	/**
	 * {@code tm1.Merge} with options.
	 * @param name the sandbox's name
	 * @param target the name of the sandbox to merge into
	 * @param conflicts what to do with cells the target changed too
	 * @param allowQueueing whether to wait for a lock rather than fail
	 * @param cleanAfter whether to empty this sandbox once merged
	 * @return completes when they're merged
	 */
	@Tm1Endpoint("Action Sandbox/Merge")
	public Mono<Void> merge(String name, String target, ConflictResolution conflicts, boolean allowQueueing,
			boolean cleanAfter) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("Target@odata.bind", sandbox(target).toString());
		body.putAll(options(conflicts, allowQueueing, cleanAfter));
		return session.execute(action(Tm1Operations.SANDBOX_MERGE, name, body));
	}

	private static Map<String, Object> options(ConflictResolution conflicts, boolean allowQueueing,
			boolean cleanAfter) {
		Map<String, Object> options = new LinkedHashMap<>();
		options.put("ConflictResolution", Objects.requireNonNull(conflicts.json(), "conflicts"));
		options.put("AllowQueueing", allowQueueing);
		options.put("CleanAfter", cleanAfter);
		return options;
	}

	private static ODataPath sandbox(String name) {
		return SANDBOXES.key(Objects.requireNonNull(name, "name"));
	}

	private static Tm1Request<Void> action(Tm1Operation operation, String name, Map<String, ?> body) {
		return Tm1Request.post(sandbox(name).action(operation.name()), body, Void.class)
			.requires(operation.capability());
	}

}

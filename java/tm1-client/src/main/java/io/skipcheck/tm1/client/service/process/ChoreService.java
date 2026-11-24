// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.process;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Chore;
import io.skipcheck.tm1.client.model.ChoreCreate;
import io.skipcheck.tm1.client.model.ChorePatch;
import io.skipcheck.tm1.client.model.ChoreTask;
import io.skipcheck.tm1.client.model.ChoreTaskPatch;
import io.skipcheck.tm1.client.model.Process;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Chores: scheduled runs of processes, as ordered tasks. {@code /Chores}, their tasks,
 * and running them now.
 *
 * <p>
 * A task binds a process ({@code ChoreTaskPatch.Builder#bindProcess}) and gives each of
 * its parameters a value; its {@code Step}, its place in the chore, is TM1's to number.
 *
 * <p>
 * Create a daily chore with one task:
 * {@snippet class = examples.ChoreServiceExamples region = create}
 *
 * <p>
 * Add a task at the end, and get the step TM1 gave it:
 * {@snippet class = examples.ChoreServiceExamples region = add-task}
 *
 * <p>
 * Schedule the chore, by patching {@code Active}:
 * {@snippet class = examples.ChoreServiceExamples region = activate}
 */
public final class ChoreService {

	private static final ODataPath CHORES = ODataPath.of("Chores");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public ChoreService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Chores}, streamed.
	 * @param query what to select, expand, filter and order
	 * @return the chores
	 */
	@Tm1Endpoint("EntitySet Chores")
	public Flux<Chore> list(ODataQuery query) {
		return session.stream(Tm1Request.list(CHORES, query, Chore.class));
	}

	/**
	 * {@code GET Chores('name')}.
	 * @param name the chore's name
	 * @param query what to select and expand, such as {@code Tasks($expand=Process)}
	 * @return the chore, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet Chores")
	public Mono<Chore> get(String name, ODataQuery query) {
		return session.execute(Tm1Request.get(chore(name), query, Chore.class));
	}

	/**
	 * {@code POST Chores}: creates a chore, with its tasks if the patch has them.
	 * @param chore the new chore; it needs a name, start time, frequency and execution
	 * mode
	 * @return the chore as created
	 */
	@Tm1Endpoint("EntitySet Chores")
	public Mono<Chore> create(ChoreCreate chore) {
		return session.execute(Tm1Request.post(CHORES, chore, Chore.class));
	}

	/**
	 * {@code PATCH Chores('name')}: changes the properties the patch sets, {@code Active}
	 * included, which IBM recommends over the deprecated {@link #activate}. Adding,
	 * changing or removing a task changes the chore's ETag too.
	 * @param name the chore's name
	 * @param patch the changes
	 * @return completes when the chore is changed
	 */
	@Tm1Endpoint("EntitySet Chores")
	public Mono<Void> update(String name, ChorePatch patch) {
		return session.execute(Tm1Request.patch(chore(name), patch));
	}

	/**
	 * {@code PATCH Chores('name')} with {@code If-Match}.
	 * @param name the chore's name
	 * @param patch the changes
	 * @param etag the ETag the chore was read with ({@link Chore#etag()})
	 * @return completes when the chore is changed, or a
	 * {@code Tm1PreconditionFailedException} if it had changed
	 */
	@Tm1Endpoint("EntitySet Chores")
	public Mono<Void> update(String name, ChorePatch patch, String etag) {
		return session.execute(Tm1Request.patch(chore(name), patch).ifMatch(etag));
	}

	/**
	 * {@code DELETE Chores('name')}; its processes stay.
	 * @param name the chore's name
	 * @return completes when the chore is deleted
	 */
	@Tm1Endpoint("EntitySet Chores")
	public Mono<Void> delete(String name) {
		return session.execute(Tm1Request.delete(chore(name)));
	}

	/**
	 * {@code GET Chores('name')/Tasks}, streamed, in step order.
	 * @param name the chore's name
	 * @param query what to select and expand, such as {@code Process}
	 * @return the tasks
	 */
	@Tm1Endpoint("Navigation Chore/Tasks")
	public Flux<ChoreTask> tasks(String name, ODataQuery query) {
		return session.stream(Tm1Request.list(chore(name).nav("Tasks"), query, ChoreTask.class));
	}

	/**
	 * {@code POST Chores('name')/Tasks}: adds a task at the end.
	 * @param name the chore's name
	 * @param task the task: its bound process and a value for each of the process's
	 * parameters
	 * @return the task as created, with the {@code Step} TM1 gave it
	 */
	@Tm1Endpoint("Navigation Chore/Tasks")
	public Mono<ChoreTask> addTask(String name, ChoreTaskPatch task) {
		return session.execute(Tm1Request.post(chore(name).nav("Tasks"), task, ChoreTask.class));
	}

	/**
	 * {@code PATCH Chores('name')/Tasks(step)}: changes a task, such as its parameter
	 * values.
	 * @param name the chore's name
	 * @param step the task's step
	 * @param patch the changes
	 * @return completes when the task is changed
	 */
	@Tm1Endpoint("Navigation Chore/Tasks")
	public Mono<Void> updateTask(String name, long step, ChoreTaskPatch patch) {
		return session.execute(Tm1Request.patch(chore(name).nav("Tasks").key(step), patch));
	}

	/**
	 * {@code DELETE Chores('name')/Tasks(step)}.
	 * @param name the chore's name
	 * @param step the task's step
	 * @return completes when the task is removed
	 */
	@Tm1Endpoint("Navigation Chore/Tasks")
	public Mono<Void> deleteTask(String name, long step) {
		return session.execute(Tm1Request.delete(chore(name).nav("Tasks").key(step)));
	}

	/**
	 * {@code GET Chores('name')/Tasks(step)/Process}.
	 * @param name the chore's name
	 * @param step the task's step
	 * @param query what to select
	 * @return the process the task runs
	 */
	@Tm1Endpoint("Navigation ChoreTask/Process")
	public Mono<Process> taskProcess(String name, long step, ODataQuery query) {
		return session.execute(Tm1Request.get(chore(name).nav("Tasks").key(step).nav("Process"), query, Process.class));
	}

	/**
	 * {@code GET Chores('name')/Tasks(step)/Chore}.
	 * @param name the chore's name
	 * @param step the task's step
	 * @param query what to select
	 * @return the task's chore
	 */
	@Tm1Endpoint("Navigation ChoreTask/Chore")
	public Mono<Chore> taskChore(String name, long step, ODataQuery query) {
		return session.execute(Tm1Request.get(chore(name).nav("Tasks").key(step).nav("Chore"), query, Chore.class));
	}

	/**
	 * {@code tm1.Execute}: runs the chore now, whether or not it's active, and completes
	 * when its tasks have run. Needs schema 10.2.2.2.
	 * @param name the chore's name
	 * @return completes when the chore has run
	 */
	@Tm1Endpoint("Action Chore/Execute")
	public Mono<Void> execute(String name) {
		return session.execute(action(Tm1Operations.CHORE_EXECUTE, name, Map.of()));
	}

	/**
	 * {@code tm1.Activate}: schedules the chore.
	 * @param name the chore's name
	 * @return completes when it's active
	 * @deprecated Deprecated in TM1 schema 11.8.3: patch {@code Active} with
	 * {@link #update}.
	 */
	@Deprecated(since = "11.8.3")
	@Tm1Endpoint("Action Chore/Activate")
	public Mono<Void> activate(String name) {
		return session.execute(action(Tm1Operations.CHORE_ACTIVATE, name, Map.of()));
	}

	/**
	 * {@code tm1.Deactivate}: unschedules the chore.
	 * @param name the chore's name
	 * @return completes when it's inactive
	 * @deprecated Deprecated in TM1 schema 11.8.3: patch {@code Active} with
	 * {@link #update}.
	 */
	@Deprecated(since = "11.8.3")
	@Tm1Endpoint("Action Chore/Deactivate")
	public Mono<Void> deactivate(String name) {
		return session.execute(action(Tm1Operations.CHORE_DEACTIVATE, name, Map.of()));
	}

	/**
	 * {@code tm1.SetServerLocalStartTime}: sets the chore's start in the server's time
	 * zone. TM1 (11.8) refuses it while the chore is active ("Can't duplicate active
	 * chore", code 7): deactivate it first.
	 * @param name the chore's name
	 * @param date the start date
	 * @param time the start time of day
	 * @return completes when it's set
	 * @deprecated Deprecated in TM1 schema 11.8.3: patch {@code StartTime} with
	 * {@link #update}.
	 */
	@Deprecated(since = "11.8.3")
	@Tm1Endpoint("Action Chore/SetServerLocalStartTime")
	public Mono<Void> setServerLocalStartTime(String name, LocalDate date, LocalTime time) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("StartDate", date.toString());
		body.put("StartTime", time.toString());
		return session.execute(action(Tm1Operations.CHORE_SET_SERVER_LOCAL_START_TIME, name, body));
	}

	private static ODataPath chore(String name) {
		return CHORES.key(Objects.requireNonNull(name, "name"));
	}

	private static Tm1Request<Void> action(Tm1Operation operation, String name, Map<String, ?> body) {
		return Tm1Request.post(chore(name).action(operation.name()), body, Void.class).requires(operation.capability());
	}

}

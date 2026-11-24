// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.skipcheck.tm1.client.conformance.Conformance;
import io.skipcheck.tm1.client.conformance.ConformanceTm1;
import io.skipcheck.tm1.client.error.Tm1NotFoundException;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.Chore;
import io.skipcheck.tm1.client.model.ChoreCreate;
import io.skipcheck.tm1.client.model.ChoreExecutionMode;
import io.skipcheck.tm1.client.model.ChorePatch;
import io.skipcheck.tm1.client.model.ChoreTask;
import io.skipcheck.tm1.client.model.ChoreTaskParameterPatch;
import io.skipcheck.tm1.client.model.ChoreTaskPatch;
import io.skipcheck.tm1.client.model.Process;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;

/**
 * ChoreService against a fake TM1: what each method sends, and how it reads TM1's answer.
 * Every call is recorded to {@code spec/conformance/calls/ChoreServiceTest.json}, which
 * the Python, .NET and TypeScript clients replay.
 */
@SuppressWarnings("deprecation")
class ChoreServiceTest {

	@RegisterExtension
	static final Conformance conformance = new Conformance();

	private ConformanceTm1 tm1() {
		return conformance.tm1();
	}

	private static ChoreTaskPatch task(String process, String version) {
		return ChoreTaskPatch.builder()
			.bindProcess(ODataPath.of("Processes").key(process))
			.parameters(List.of(ChoreTaskParameterPatch.builder().name("pVersion").value(version).build(),
					ChoreTaskParameterPatch.builder().name("pYear").value(2026).build()))
			.build();
	}

	@Test
	void listsChores() {
		tm1().json("{\"value\":[{\"Name\":\"Nightly\",\"Active\":true,\"ExecutionMode\":\"SingleCommit\"}]}");
		List<Chore> chores = conformance.call("chores", "list",
				ODataQuery.none().select("Name", "Active").expand("Tasks"));
		assertThat(chores).extracting(Chore::name).containsExactly("Nightly");
		assertThat(tm1().last().target()).isEqualTo("Chores?$select=Name,Active&$expand=Tasks");
	}

	@Test
	void getsAChoreWithItsTasksAndProcesses() {
		tm1().json("{\"@odata.etag\":\"W/\\\"3\\\"\",\"Name\":\"Nightly\",\"StartTime\":\"2026-01-01T02:00Z\","
				+ "\"Frequency\":\"P1DT00H00M00S\",\"DSTSensitive\":false,\"ExecutionMode\":\"MultipleCommit\","
				+ "\"Tasks\":[{\"Step\":0,\"Process\":{\"Name\":\"Load\"}}]}");
		Chore chore = conformance.call("chores", "get", "Nightly",
				ODataQuery.none().expand("Tasks", q -> q.expand("Process")));
		assertThat(chore.etag()).isEqualTo("W/\"3\"");
		assertThat(chore.executionMode()).isEqualTo(ChoreExecutionMode.MULTIPLE_COMMIT);
		assertThat(chore.frequency()).isEqualTo(Duration.ofDays(1));
		assertThat(tm1().last().target()).isEqualTo("Chores('Nightly')?$expand=Tasks($expand%3DProcess)");
	}

	@Test
	void quotesAName() {
		tm1().json("{\"Name\":\"Joe's nightly #2\"}");
		conformance.call("chores", "get", "Joe's nightly #2", ODataQuery.none());
		assertThat(tm1().last().target()).isEqualTo("Chores('Joe''s%20nightly%20%232')");
	}

	@Test
	void aMissingChoreIsNotFound() {
		tm1().error(404, "278", "'Nope' can not be found in collection of type 'Chore'.");
		Throwable error = conformance.fail("chores", "get", "Nope", ODataQuery.none());
		assertThat(error).isInstanceOf(Tm1NotFoundException.class);
	}

	@Test
	void createsAChoreWithItsTasks() {
		tm1().json(201, "{\"Name\":\"Nightly\"}");
		Chore created = conformance.call("chores", "create",
				ChoreCreate.builder()
					.name("Nightly")
					.startTime(OffsetDateTime.parse("2026-01-01T02:00:00Z"))
					.dstSensitive(false)
					.frequency(Duration.ofDays(1))
					.executionMode(ChoreExecutionMode.SINGLE_COMMIT)
					.active(false)
					.tasks(List.of(task("Load", "Actual")))
					.build());
		assertThat(created.name()).isEqualTo("Nightly");
		assertThat(tm1().last().key()).isEqualTo("POST Chores");
	}

	@Test
	void createsAChoreWithAnOffsetStart() {
		tm1().json(201, "{\"Name\":\"Hourly\"}");
		conformance.call("chores", "create",
				ChoreCreate.builder()
					.name("Hourly")
					.startTime(OffsetDateTime.parse("2026-03-04T05:06:07.5+02:00"))
					.frequency(Duration.ofMinutes(90))
					.executionMode(ChoreExecutionMode.MULTIPLE_COMMIT)
					.build());
		assertThat(tm1().last().key()).isEqualTo("POST Chores");
	}

	@Test
	void anExistingChoreConflicts() {
		tm1().error(409, "278", "Chore 'Nightly' already exists");
		conformance.fail("chores", "create", ChoreCreate.builder().name("Nightly").build());
	}

	@Test
	void updatesWithAndWithoutAnEtag() {
		tm1().status(204);
		conformance.call("chores", "update", "Nightly", ChorePatch.builder().active(true).build());
		assertThat(tm1().last().key()).isEqualTo("PATCH Chores('Nightly')");
		assertThat(tm1().last().text()).isEqualTo("{\"Active\":true}");
		assertThat(tm1().last().header("If-Match")).isNull();

		tm1().status(204);
		conformance.call("chores", "update", "Nightly", ChorePatch.builder().frequency(Duration.ofHours(6)).build(),
				"W/\"3\"");
		assertThat(tm1().last().header("If-Match")).isEqualTo("W/\"3\"");
	}

	@Test
	void aStaleEtagIsRefused() {
		tm1().error(412, "278", "stale");
		Throwable error = conformance.fail("chores", "update", "Nightly", ChorePatch.builder().active(true).build(),
				"W/\"0\"");
		assertThat(error).isInstanceOf(Tm1PreconditionFailedException.class);
	}

	@Test
	void deletesAChore() {
		tm1().status(204);
		conformance.call("chores", "delete", "Nightly");
		assertThat(tm1().last().key()).isEqualTo("DELETE Chores('Nightly')");
	}

	@Test
	void worksWithTasksByStep() {
		tm1().json("{\"value\":[{\"Step\":0,\"Parameters\":[{\"Name\":\"pVersion\",\"Value\":\"Actual\"}]},"
				+ "{\"Step\":1}]}");
		List<ChoreTask> tasks = conformance.call("chores", "tasks", "Nightly", ODataQuery.none().expand("Process"));
		assertThat(tasks).extracting(ChoreTask::step).containsExactly(0L, 1L);
		assertThat(tm1().last().target()).isEqualTo("Chores('Nightly')/Tasks?$expand=Process");

		tm1().json(201, "{\"Step\":2}");
		ChoreTask added = conformance.call("chores", "addTask", "Nightly", task("Load", "Forecast"));
		assertThat(added.step()).isEqualTo(2L);
		assertThat(tm1().last().key()).isEqualTo("POST Chores('Nightly')/Tasks");
		assertThat(tm1().last().text()).isEqualTo("{\"Process@odata.bind\":\"Processes('Load')\",\"Parameters\":"
				+ "[{\"Name\":\"pVersion\",\"Value\":\"Forecast\"},{\"Name\":\"pYear\",\"Value\":2026}]}");

		tm1().status(204);
		conformance.call("chores", "updateTask", "Nightly", 2L,
				ChoreTaskPatch.builder()
					.parameters(List.of(ChoreTaskParameterPatch.builder().name("pVersion").value("Budget").build()))
					.build());
		assertThat(tm1().last().key()).isEqualTo("PATCH Chores('Nightly')/Tasks(2)");

		tm1().status(204);
		conformance.call("chores", "deleteTask", "Nightly", 0L);
		assertThat(tm1().last().key()).isEqualTo("DELETE Chores('Nightly')/Tasks(0)");

		tm1().json("{\"Name\":\"Load\"}");
		Process process = conformance.call("chores", "taskProcess", "Nightly", 1L, ODataQuery.none().select("Name"));
		assertThat(process.name()).isEqualTo("Load");
		assertThat(tm1().last().target()).isEqualTo("Chores('Nightly')/Tasks(1)/Process?$select=Name");

		tm1().json("{\"Name\":\"Nightly\"}");
		Chore chore = conformance.call("chores", "taskChore", "Nightly", 1L, ODataQuery.none());
		assertThat(chore.name()).isEqualTo("Nightly");
		assertThat(tm1().last().target()).isEqualTo("Chores('Nightly')/Tasks(1)/Chore");
	}

	@Test
	void aMissingTaskIsNotFound() {
		tm1().error(404, "278", "Task 9 can not be found");
		conformance.fail("chores", "taskProcess", "Nightly", 9L, ODataQuery.none());
	}

	@Test
	void runsTheChoresActions() {
		for (String action : List.of("execute", "activate", "deactivate")) {
			tm1().status(204);
			conformance.call("chores", action, "Nightly");
			String name = Character.toUpperCase(action.charAt(0)) + action.substring(1);
			assertThat(tm1().last().key()).isEqualTo("POST Chores('Nightly')/tm1." + name);
			assertThat(tm1().last().text()).isEqualTo("{}");
		}
	}

	@Test
	void setsTheServerLocalStartTime() {
		tm1().status(204);
		conformance.call("chores", "setServerLocalStartTime", "Nightly", LocalDate.of(2026, 1, 2), LocalTime.of(3, 4));
		assertThat(tm1().last().key()).isEqualTo("POST Chores('Nightly')/tm1.SetServerLocalStartTime");
		assertThat(tm1().last().text()).isEqualTo("{\"StartDate\":\"2026-01-02\",\"StartTime\":\"03:04\"}");

		tm1().status(204);
		conformance.call("chores", "setServerLocalStartTime", "Nightly", LocalDate.of(2026, 12, 31),
				LocalTime.of(23, 59, 30, 500_000_000));
		assertThat(tm1().last().text()).isEqualTo("{\"StartDate\":\"2026-12-31\",\"StartTime\":\"23:59:30.500\"}");
	}

	@Test
	void anActiveChoresStartIsRefused() {
		tm1().error(400, "7", "Can't duplicate active chore");
		conformance.fail("chores", "setServerLocalStartTime", "Nightly", LocalDate.of(2026, 1, 2), LocalTime.of(3, 4));
	}

	@Test
	void refusesAMissingName() {
		Throwable error = conformance.fail("chores", "execute", (Object) null);
		assertThat(error).isInstanceOf(NullPointerException.class);
		assertThat(tm1().requests()).isEmpty();
	}

}

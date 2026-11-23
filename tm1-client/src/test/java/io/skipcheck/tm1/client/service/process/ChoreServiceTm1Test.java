package io.skipcheck.tm1.client.service.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.error.Tm1Exception;
import io.skipcheck.tm1.client.error.Tm1PreconditionFailedException;
import io.skipcheck.tm1.client.model.Chore;
import io.skipcheck.tm1.client.model.ChoreExecutionMode;
import io.skipcheck.tm1.client.model.ChorePatch;
import io.skipcheck.tm1.client.model.ChoreTask;
import io.skipcheck.tm1.client.model.ChoreTaskParameterPatch;
import io.skipcheck.tm1.client.model.ChoreTaskPatch;
import io.skipcheck.tm1.client.model.ProcessParameterPatch;
import io.skipcheck.tm1.client.model.ProcessPatch;
import io.skipcheck.tm1.client.model.ProcessVariableType;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.testcontainers.RequiresTm1;
import io.skipcheck.tm1.testcontainers.Tm1ServerContainer;

/** ChoreService, with a process that creates the dimension its pName parameter names. */
@RequiresTm1
class ChoreServiceTm1Test {

	private static final Duration WAIT = Duration.ofSeconds(60);

	private Tm1Client tm1;

	private ChoreService chores;

	private String id;

	private String process;

	private String chore;

	@BeforeEach
	void createProcessAndChore(Tm1ServerContainer server) {
		tm1 = Tm1Client.builder()
			.baseUrl(server.getRestUrl())
			.basicAuth(server.getUsername(), server.getPassword())
			.build();
		chores = tm1.chores();
		id = UUID.randomUUID().toString().substring(0, 8);
		process = "tm1-client-" + id;
		chore = "tm1-client-chore-" + id;
		tm1.processes()
			.create(ProcessPatch.builder()
				.name(process)
				.parameters(List.of(
						ProcessParameterPatch.builder()
							.name("pName")
							.value("")
							.type(ProcessVariableType.STRING)
							.build(),
						ProcessParameterPatch.builder()
							.name("pCount")
							.value(0)
							.type(ProcessVariableType.NUMERIC)
							.build()))
				.prologProcedure("If(DimensionExists(pName) = 0);\n  DimensionCreate(pName);\nEndIf;\n")
				.build())
			.block(WAIT);
		chores
			.create(ChorePatch.builder()
				.name(chore)
				.startTime(OffsetDateTime.parse("2026-01-01T02:00:00Z"))
				.dstSensitive(false)
				.active(false)
				.executionMode(ChoreExecutionMode.SINGLE_COMMIT)
				.frequency(Duration.ofDays(1))
				.tasks(List.of(task("marker-" + id)))
				.build())
			.block(WAIT);
	}

	@AfterEach
	void deleteProcessAndChore() {
		chores.delete(chore).onErrorComplete().block(WAIT);
		tm1.processes().delete(process).block(WAIT);
		for (String d : List.of("marker-" + id, "second-" + id)) {
			tm1.dimensions().delete(d).onErrorComplete().block(WAIT);
		}
		tm1.close().block(WAIT);
	}

	@Test
	void createsReadsUpdatesAndDeletesAChoreAndItsTasks() {
		Chore read = chores.get(chore, ODataQuery.none().expand(Chore.TASKS, t -> t.expand(ChoreTask.PROCESS)))
			.block(WAIT);
		assertThat(read.tasks()).singleElement().satisfies(t -> {
			assertThat(t.step()).as("TM1 numbers the steps from 0").isEqualTo(0L);
			assertThat(t.process().name()).isEqualTo(process);
		});
		assertThat(chores.taskProcess(chore, 0, ODataQuery.none()).block(WAIT).name()).isEqualTo(process);
		assertThat(chores.taskChore(chore, 0, ODataQuery.none()).block(WAIT).name()).isEqualTo(chore);

		assertThat(chores.addTask(chore, task("second-" + id)).block(WAIT).step()).isEqualTo(1L);
		assertThat(chores.tasks(chore, ODataQuery.none()).map(ChoreTask::step).collectList().block(WAIT))
			.containsExactly(0L, 1L);
		chores
			.updateTask(chore, 1,
					ChoreTaskPatch.builder()
						.parameters(List.of(parameter("pName", "third-" + id), parameter("pCount", 2)))
						.build())
			.block(WAIT);
		assertThat(chores.tasks(chore, ODataQuery.none()).collectList().block(WAIT).get(1).parameters())
			.anySatisfy(p -> assertThat(p.value().asString()).isEqualTo("third-" + id));
		chores.deleteTask(chore, 0).block(WAIT);
		assertThat(chores.tasks(chore, ODataQuery.none()).map(ChoreTask::step).collectList().block(WAIT))
			.as("renumbered from 0")
			.containsExactly(0L);

		String etag = chores.get(chore, ODataQuery.none()).block(WAIT).etag();
		assertThat(etag).as("task changes change the chore's ETag").isNotEqualTo(read.etag());
		chores.update(chore, ChorePatch.builder().frequency(Duration.ofHours(6)).build(), etag).block(WAIT);
		assertThat(chores.get(chore, ODataQuery.none()).block(WAIT).frequency()).isEqualTo(Duration.ofHours(6));
		assertThatThrownBy(() -> chores.update(chore, ChorePatch.builder().frequency(Duration.ofHours(1)).build(), etag)
			.block(WAIT)).isInstanceOf(Tm1PreconditionFailedException.class);
	}

	@Test
	void needsEveryProcessParameterInATask() {
		assertThatThrownBy(() -> chores
			.addTask(chore,
					ChoreTaskPatch.builder()
						.bindProcess(ODataPath.of("Processes").key(process))
						.parameters(List.of(parameter("pName", "x")))
						.build())
			.block(WAIT)).isInstanceOf(Tm1Exception.class);
	}

	@Test
	void activatesByPatchOrTheDeprecatedActions() {
		chores.update(chore, ChorePatch.builder().active(true).build()).block(WAIT);
		assertThat(active()).as("by PATCH, as IBM recommends").isTrue();
		deprecated();
	}

	@SuppressWarnings("deprecation")
	private void deprecated() {
		chores.deactivate(chore).block(WAIT);
		assertThat(active()).isFalse();
		chores.activate(chore).block(WAIT);
		assertThat(active()).isTrue();
		assertThatThrownBy(
				() -> chores.setServerLocalStartTime(chore, LocalDate.of(2027, 3, 4), LocalTime.of(5, 30)).block(WAIT))
			.as("not while it's active")
			.isInstanceOf(Tm1Exception.class)
			.hasMessageContaining("active chore");
		chores.deactivate(chore).block(WAIT);
		chores.setServerLocalStartTime(chore, LocalDate.of(2027, 3, 4), LocalTime.of(5, 30)).block(WAIT);
		assertThat(chores.get(chore, ODataQuery.none()).block(WAIT).startTime().toLocalDate())
			.isEqualTo(LocalDate.of(2027, 3, 4));
	}

	@Test
	void runsTheChoreNow() {
		chores.execute(chore).block(WAIT);
		assertThat(tm1.dimensions().get("marker-" + id, ODataQuery.none()).block(WAIT).name())
			.isEqualTo("marker-" + id);
	}

	private boolean active() {
		return Boolean.TRUE.equals(chores.get(chore, ODataQuery.none()).block(WAIT).active());
	}

	private ChoreTaskPatch task(String marker) {
		return ChoreTaskPatch.builder()
			.bindProcess(ODataPath.of("Processes").key(process))
			.parameters(List.of(parameter("pName", marker), parameter("pCount", 1)))
			.build();
	}

	private static ChoreTaskParameterPatch parameter(String name, Object value) {
		return ChoreTaskParameterPatch.builder().name(name).value(value).build();
	}

}

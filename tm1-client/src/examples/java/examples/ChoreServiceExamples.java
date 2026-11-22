package examples;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.Chore;
import net.ajmyers.tm1.client.model.ChoreExecutionMode;
import net.ajmyers.tm1.client.model.ChorePatch;
import net.ajmyers.tm1.client.model.ChoreTask;
import net.ajmyers.tm1.client.model.ChoreTaskParameterPatch;
import net.ajmyers.tm1.client.model.ChoreTaskPatch;
import net.ajmyers.tm1.client.odata.ODataPath;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link net.ajmyers.tm1.client.service.process.ChoreService}, quoted in its
 * Javadoc.
 */
final class ChoreServiceExamples {

	Mono<Chore> create(Tm1Client tm1) {
		// @start region = create
		// A daily chore at 02:00 UTC, inactive until it's switched on. A task gives
		// every parameter of its process a value.
		ChoreTaskPatch loadActuals = ChoreTaskPatch.builder()
			.bindProcess(ODataPath.of("Processes").key("Load Sales"))
			.parameters(List.of(ChoreTaskParameterPatch.builder().name("pVersion").value("Actual").build(),
					ChoreTaskParameterPatch.builder().name("pYear").value(2026).build()))
			.build();
		return tm1.chores()
			.create(ChorePatch.builder()
				.name("Nightly Load")
				.startTime(OffsetDateTime.parse("2026-01-01T02:00:00Z"))
				.dstSensitive(false)
				.frequency(Duration.ofDays(1))
				.executionMode(ChoreExecutionMode.SINGLE_COMMIT)
				.active(false)
				.tasks(List.of(loadActuals))
				.build());
		// @end
	}

	Mono<Long> addTask(Tm1Client tm1) {
		// @start region = add-task
		// A task goes at the end; TM1 numbers the steps from 0
		ChoreTaskPatch loadForecast = ChoreTaskPatch.builder()
			.bindProcess(ODataPath.of("Processes").key("Load Sales"))
			.parameters(List.of(ChoreTaskParameterPatch.builder().name("pVersion").value("Forecast").build(),
					ChoreTaskParameterPatch.builder().name("pYear").value(2026).build()))
			.build();
		return tm1.chores().addTask("Nightly Load", loadForecast).mapNotNull(ChoreTask::step);
		// @end
	}

	Mono<Void> activate(Tm1Client tm1) {
		// @start region = activate
		// Scheduling a chore is a patch of Active
		return tm1.chores().update("Nightly Load", ChorePatch.builder().active(true).build());
		// @end
	}

	private ChoreServiceExamples() {
	}

}

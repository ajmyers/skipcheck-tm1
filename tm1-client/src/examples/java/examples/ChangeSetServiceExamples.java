package examples;

import java.util.LinkedHashMap;
import java.util.Map;

import net.ajmyers.tm1.client.Tm1Client;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link net.ajmyers.tm1.client.service.data.ChangeSetService}, quoted in
 * its Javadoc.
 */
final class ChangeSetServiceExamples {

	Mono<String> within(Tm1Client tm1) {
		// @start region = within
		// Record some writes in a change set, and keep its ID to undo them later
		Map<String, String> cell = new LinkedHashMap<>();
		cell.put("Version", "Forecast");
		cell.put("Region", "North");
		cell.put("Month", "Jan");
		cell.put("Measure", "Revenue");
		return tm1.changeSets().within(session -> {
			String id = session.changeSet().orElseThrow();
			return Tm1Client.of(session).cells().update("Sales", cell, 1500).thenReturn(id);
		}).single();
		// @end
	}

	Mono<Void> undo(Tm1Client tm1, String changeSet) {
		// @start region = undo
		// Revert every write recorded in the change set; writes outside it stay
		return tm1.changeSets().undo(changeSet);
		// @end
	}

	private ChangeSetServiceExamples() {
	}

}

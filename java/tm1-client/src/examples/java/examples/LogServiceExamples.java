// SPDX-License-Identifier: Apache-2.0

package examples;

import java.time.Duration;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.LogLevel;
import io.skipcheck.tm1.client.model.MessageLogEntry;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.server.LogService}, quoted in its
 * Javadoc.
 */
@SuppressWarnings("deprecation") // MessageLogEntry is deprecated in TM1 schema 12.0.0;
									// active on TM1 11.8
final class LogServiceExamples {

	Flux<MessageLogEntry> newest(Tm1Client tm1) {
		// @start region = newest
		// The 50 newest entries, newest first
		return tm1.logs().messageLog(true, ODataQuery.none().top(50));
		// @end
	}

	Flux<String> follow(Tm1Client tm1) {
		// @start region = follow
		// Errors as they're written, until the subscriber cancels; each poll is every 5 s
		return tm1.logs()
			.tailMessageLog(Duration.ofSeconds(5))
			.filter(entry -> entry.level() == LogLevel.ERROR)
			.map(entry -> entry.logger() + ": " + entry.message());
		// @end
	}

	Flux<String> processErrors(Tm1Client tm1) {
		// @start region = process-errors
		// What each error log of one process says
		return tm1.logs()
			.errorLogFiles(ODataQuery.none())
			.filter(file -> file.filename() != null && file.filename().endsWith("_Load Sales.log"))
			.concatMap(file -> tm1.logs().errorLogContent(file.filename()));
		// @end
	}

	Mono<Long> changes(Tm1Client tm1) {
		// @start region = changes
		// How many cell changes the transaction log holds, leaving out its status lines
		return tm1.logs().transactionLog(false, true, ODataQuery.none()).count();
		// @end
	}

	private LogServiceExamples() {
	}

}

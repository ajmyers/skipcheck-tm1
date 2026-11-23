package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.dimension.LevelService}, quoted in
 * its Javadoc.
 */
final class LevelServiceExamples {

	Flux<String> list(Tm1Client tm1) {
		// @start region = list
		// Each level's number and how many members it has, as "1: 2"
		return tm1.levels()
			.list("Region", "Region", ODataQuery.none())
			.map(level -> level.number() + ": " + level.cardinality());
		// @end
	}

	Flux<String> members(Tm1Client tm1) {
		// @start region = members
		// Levels count from the top: level 0 holds the roots, level 1 their children
		return tm1.levels().members("Region", "Region", 1, ODataQuery.none().select(Member.NAME)).map(Member::name);
		// @end
	}

	private LevelServiceExamples() {
	}

}

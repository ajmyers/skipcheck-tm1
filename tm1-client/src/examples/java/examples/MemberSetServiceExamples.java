package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.CellsetAxis;
import io.skipcheck.tm1.client.model.Member;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.data.MemberSetService}, quoted in
 * its Javadoc.
 */
final class MemberSetServiceExamples {

	Flux<String> names(Tm1Client tm1) {
		// @start region = names
		// The names of Region's leaves, with the member set deleted once they're read
		return Flux.usingWhen(
				tm1.memberSets()
					.execute("{TM1FILTERBYLEVEL({TM1SUBSETALL([Region].[Region])}, 0)}",
							ODataQuery.none()
								.expand(CellsetAxis.TUPLES, t -> t.expand("Members", m -> m.select(Member.NAME)))),
				set -> Flux.fromIterable(set.tuples()).map(tuple -> tuple.members().get(0).name()),
				set -> tm1.memberSets().delete(set.ordinal()));
		// @end
	}

	Mono<Integer> keep(Tm1Client tm1) {
		// @start region = keep
		// Keep a large set on the server and read it later by its ordinal; TM1 doesn't
		// list member sets, so hold on to the ordinal, and delete the set when done
		return tm1.memberSets()
			.execute("{TM1SUBSETALL([Product].[Product])}", ODataQuery.none())
			.map(CellsetAxis::ordinal);
		// @end
	}

	private MemberSetServiceExamples() {
	}

}

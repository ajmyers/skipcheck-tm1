package examples;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.Member;
import net.ajmyers.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link net.ajmyers.tm1.client.service.dimension.MemberService}, quoted in
 * its Javadoc.
 */
final class MemberServiceExamples {

	Mono<String> uniqueName(Tm1Client tm1) {
		// @start region = unique-name
		// A member's unique name, such as [Region].[Region].[North], for use in MDX
		return tm1.members()
			.get("Region", "Region", "North", ODataQuery.none().select(Member.UNIQUE_NAME))
			.map(Member::uniqueName);
		// @end
	}

	Flux<String> children(Tm1Client tm1) {
		// @start region = children
		// The children of Total, in order
		return tm1.members()
			.children("Region", "Region", "Total", ODataQuery.none().select(Member.NAME))
			.map(Member::name);
		// @end
	}

	Mono<String> parent(Tm1Client tm1) {
		// @start region = parent
		// The parent of North (the first, if it has several); a root has none
		return tm1.members()
			.parent("Region", "Region", "North", ODataQuery.none().select(Member.NAME))
			.map(Member::name)
			.defaultIfEmpty("(a root)");
		// @end
	}

	private MemberServiceExamples() {
	}

}

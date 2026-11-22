package examples;

import java.util.stream.Stream;

import net.ajmyers.tm1.client.Tm1Client;
import net.ajmyers.tm1.client.model.Group;
import net.ajmyers.tm1.client.model.User;
import net.ajmyers.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link net.ajmyers.tm1.client.service.security.GroupService}, quoted in
 * its Javadoc.
 */
final class GroupServiceExamples {

	Flux<String> members(Tm1Client tm1) {
		// @start region = members
		return tm1.groups().users("Planners", ODataQuery.none().select(User.NAME)).map(User::name);
		// @end
	}

	Mono<Void> createAndAdd(Tm1Client tm1) {
		// @start region = create
		// A new group, then alice added to it, from her side: her groups plus this one
		return tm1.groups()
			.create("Planners")
			.then(tm1.users().groups("alice", ODataQuery.none().select(Group.NAME)).map(Group::name).collectList())
			.flatMap(groups -> tm1.users()
				.setGroups("alice", Stream.concat(groups.stream(), Stream.of("Planners")).toList()));
		// @end
	}

	private GroupServiceExamples() {
	}

}

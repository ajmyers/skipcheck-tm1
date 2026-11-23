// SPDX-License-Identifier: Apache-2.0

package examples;

import io.skipcheck.tm1.client.Tm1Client;
import io.skipcheck.tm1.client.model.Group;
import io.skipcheck.tm1.client.model.User;
import io.skipcheck.tm1.client.model.UserPatch;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import reactor.core.publisher.Mono;

/**
 * Examples for {@link io.skipcheck.tm1.client.service.security.UserService}, quoted in
 * its Javadoc.
 */
final class UserServiceExamples {

	Mono<User> create(Tm1Client tm1) {
		// @start region = create
		// A user in two existing groups, bound by their paths
		ODataPath groups = ODataPath.of("Groups");
		return tm1.users()
			.create(UserPatch.builder()
				.name("alice")
				.password("initial-password")
				.bindGroups(groups.key("Planners"), groups.key("Sales"))
				.build());
		// @end
	}

	Mono<Void> leaveGroup(Tm1Client tm1) {
		// @start region = leave-group
		// Groups are set whole: keep every group but Planners
		return tm1.users()
			.groups("alice", ODataQuery.none().select(Group.NAME))
			.map(Group::name)
			.filter(group -> !group.equals("Planners"))
			.collectList()
			.flatMap(rest -> tm1.users().setGroups("alice", rest));
		// @end
	}

	Mono<Void> lockOut(Tm1Client tm1) {
		// @start region = lock-out
		// Stop the user logging in, then end the sessions they already have
		return tm1.users()
			.update("alice", UserPatch.builder().enabled(false).build())
			.then(tm1.users().disconnect("alice"));
		// @end
	}

	private UserServiceExamples() {
	}

}

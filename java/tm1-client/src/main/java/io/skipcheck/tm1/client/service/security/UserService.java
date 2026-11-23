// SPDX-License-Identifier: Apache-2.0

package io.skipcheck.tm1.client.service.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.skipcheck.tm1.client.Tm1Operation;
import io.skipcheck.tm1.client.Tm1Request;
import io.skipcheck.tm1.client.model.Group;
import io.skipcheck.tm1.client.model.Session;
import io.skipcheck.tm1.client.model.Tm1Operations;
import io.skipcheck.tm1.client.model.User;
import io.skipcheck.tm1.client.model.UserPatch;
import io.skipcheck.tm1.client.odata.ODataPath;
import io.skipcheck.tm1.client.odata.ODataQuery;
import io.skipcheck.tm1.client.service.Tm1Endpoint;
import io.skipcheck.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Users: {@code /Users}, the session's own user, their groups, and disconnecting them.
 *
 * <p>
 * Group membership is set whole, with {@link #setGroups}: binding {@code Groups} in a
 * user PATCH replaces them. TM1 (11.8) has no safe way to leave one group: a
 * {@code DELETE Users('u')/Groups('g')/$ref}, which OData means as "remove the link",
 * <em>deletes the group</em>, for everyone. This service never sends it.
 *
 * <p>
 * Create a user in some groups, binding them by path:
 * {@snippet class = examples.UserServiceExamples region = create}
 *
 * <p>
 * Take a user out of one group by setting the rest:
 * {@snippet class = examples.UserServiceExamples region = leave-group}
 *
 * <p>
 * Lock a user out: disable them, then close their sessions.
 * {@snippet class = examples.UserServiceExamples region = lock-out}
 */
public final class UserService {

	private static final ODataPath USERS = ODataPath.of("Users");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public UserService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Users}, streamed.
	 * @param query what to select, expand, filter and order
	 * @return the users
	 */
	@Tm1Endpoint("EntitySet Users")
	public Flux<User> list(ODataQuery query) {
		return session.stream(Tm1Request.list(USERS, query, User.class));
	}

	/**
	 * {@code GET Users('name')}. User names match ignoring case and spaces.
	 * @param name the user's name
	 * @param query what to select and expand
	 * @return the user, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet Users")
	public Mono<User> get(String name, ODataQuery query) {
		return session.execute(Tm1Request.get(user(name), query, User.class));
	}

	/**
	 * {@code GET ActiveUser}: the user this session is for.
	 * @param query what to select and expand
	 * @return the user
	 */
	@Tm1Endpoint("Singleton ActiveUser")
	public Mono<User> activeUser(ODataQuery query) {
		return session.execute(Tm1Request.get(ODataPath.of("ActiveUser"), query, User.class));
	}

	/**
	 * {@code POST Users}: creates a user, in the groups the patch binds
	 * ({@code UserPatch.Builder#bindGroups}).
	 * @param user the new user; it needs a name, and a password for Basic security
	 * @return the user as created
	 */
	@Tm1Endpoint("EntitySet Users")
	public Mono<User> create(UserPatch user) {
		return session.execute(Tm1Request.post(USERS, user, User.class));
	}

	/**
	 * {@code PATCH Users('name')}: changes the properties the patch sets, such as
	 * {@code Password} or {@code Enabled}. TM1 (11.8) sends no ETag for a user, so
	 * there's no {@code If-Match} form.
	 * @param name the user's name
	 * @param patch the changes, without {@code Groups}
	 * @return completes when the user is changed
	 * @throws IllegalArgumentException if the patch sets or binds {@code Groups}, which
	 * replaces them: use {@link #setGroups}
	 */
	@Tm1Endpoint("EntitySet Users")
	public Mono<Void> update(String name, UserPatch patch) {
		return session.execute(Tm1Request.patch(user(name), withoutGroups(patch)));
	}

	/**
	 * {@code PATCH Users('name')} binding {@code Groups}: makes the user's groups exactly
	 * these, leaving any not listed.
	 * @param name the user's name
	 * @param groups the names of every group the user should be in
	 * @return completes when the groups are set
	 */
	@Tm1Endpoint("EntitySet Users")
	@Tm1Endpoint("Navigation User/Groups")
	public Mono<Void> setGroups(String name, List<String> groups) {
		List<String> bindings = new ArrayList<>();
		for (String group : groups) {
			bindings.add(ODataPath.of("Groups").key(Objects.requireNonNull(group, "group")).toString());
		}
		return session.execute(Tm1Request.patch(user(name), Map.of("Groups@odata.bind", bindings)));
	}

	/**
	 * {@code DELETE Users('name')}.
	 * @param name the user's name
	 * @return completes when the user is deleted
	 */
	@Tm1Endpoint("EntitySet Users")
	public Mono<Void> delete(String name) {
		return session.execute(Tm1Request.delete(user(name)));
	}

	/**
	 * {@code GET Users('name')/Groups}, streamed.
	 * @param name the user's name
	 * @param query what to select
	 * @return the user's groups
	 */
	@Tm1Endpoint("Navigation User/Groups")
	public Flux<Group> groups(String name, ODataQuery query) {
		return session.stream(Tm1Request.list(user(name).nav("Groups"), query, Group.class));
	}

	/**
	 * {@code GET Users('name')/Sessions}, streamed: the user's sessions on the server.
	 * @param name the user's name
	 * @param query what to select and expand
	 * @return the user's sessions
	 */
	@Tm1Endpoint("Navigation User/Sessions")
	public Flux<Session> sessions(String name, ODataQuery query) {
		return session.stream(Tm1Request.list(user(name).nav("Sessions"), query, Session.class));
	}

	/**
	 * {@code tm1.Disconnect}: closes every session the user has. Needs schema 10.2.2.6.
	 * @param name the user's name
	 * @return completes when they're disconnected
	 */
	@Tm1Endpoint("Action User/Disconnect")
	public Mono<Void> disconnect(String name) {
		Tm1Operation operation = Tm1Operations.USER_DISCONNECT;
		return session.execute(Tm1Request.post(user(name).action(operation.name()), Map.of(), Void.class)
			.requires(operation.capability()));
	}

	private static UserPatch withoutGroups(UserPatch patch) {
		if (patch.json().keySet().stream().anyMatch(key -> key.equals("Groups") || key.startsWith("Groups@"))) {
			throw new IllegalArgumentException("A user PATCH with Groups replaces them: use setGroups");
		}
		return patch;
	}

	private static ODataPath user(String name) {
		return USERS.key(Objects.requireNonNull(name, "name"));
	}

}

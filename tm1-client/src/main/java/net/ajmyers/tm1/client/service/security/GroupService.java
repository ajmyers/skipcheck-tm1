package net.ajmyers.tm1.client.service.security;

import java.util.Map;
import java.util.Objects;

import net.ajmyers.tm1.client.Tm1Request;
import net.ajmyers.tm1.client.model.Group;
import net.ajmyers.tm1.client.model.User;
import net.ajmyers.tm1.client.odata.ODataPath;
import net.ajmyers.tm1.client.odata.ODataQuery;
import net.ajmyers.tm1.client.service.Tm1Endpoint;
import net.ajmyers.tm1.client.session.Tm1Session;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Security groups: {@code /Groups} and their users. A user's membership is set from the
 * user's side, with {@link UserService#setGroups}.
 */
public final class GroupService {

	private static final ODataPath GROUPS = ODataPath.of("Groups");

	private final Tm1Session session;

	/**
	 * A service sending its requests through {@code session}.
	 * @param session the session
	 */
	public GroupService(Tm1Session session) {
		this.session = Objects.requireNonNull(session, "session");
	}

	/**
	 * {@code GET Groups}, streamed.
	 * @param query what to select, expand, filter and order
	 * @return the groups
	 */
	@Tm1Endpoint("EntitySet Groups")
	public Flux<Group> list(ODataQuery query) {
		return session.stream(Tm1Request.list(GROUPS, query, Group.class));
	}

	/**
	 * {@code GET Groups('name')}.
	 * @param name the group's name
	 * @param query what to select and expand
	 * @return the group, or a {@code Tm1NotFoundException} if there's none
	 */
	@Tm1Endpoint("EntitySet Groups")
	public Mono<Group> get(String name, ODataQuery query) {
		return session.execute(Tm1Request.get(group(name), query, Group.class));
	}

	/**
	 * {@code POST Groups}: creates an empty group.
	 * @param name the group's name
	 * @return the group as created
	 */
	@Tm1Endpoint("EntitySet Groups")
	public Mono<Group> create(String name) {
		return session
			.execute(Tm1Request.post(GROUPS, Map.of("Name", Objects.requireNonNull(name, "name")), Group.class));
	}

	/**
	 * {@code DELETE Groups('name')}: deletes the group; its users stay, without it.
	 * @param name the group's name
	 * @return completes when the group is deleted
	 */
	@Tm1Endpoint("EntitySet Groups")
	public Mono<Void> delete(String name) {
		return session.execute(Tm1Request.delete(group(name)));
	}

	/**
	 * {@code GET Groups('name')/Users}, streamed.
	 * @param name the group's name
	 * @param query what to select
	 * @return the group's users
	 */
	@Tm1Endpoint("Navigation Group/Users")
	public Flux<User> users(String name, ODataQuery query) {
		return session.stream(Tm1Request.list(group(name).nav("Users"), query, User.class));
	}

	private static ODataPath group(String name) {
		return GROUPS.key(Objects.requireNonNull(name, "name"));
	}

}

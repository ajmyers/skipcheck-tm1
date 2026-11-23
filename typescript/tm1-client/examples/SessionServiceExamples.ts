import { ODataQuery, Session, User, type Tm1Client } from "@skipcheck/tm1-client";

// Examples for SessionService, quoted in its documentation.

export async function list(tm1: Tm1Client): Promise<string[]> {
  // @start region = list
  // Every session on the server, with its user's name and its label
  const withUsers = ODataQuery.none()
    .select(Session.Fields.ID, Session.Fields.Context)
    .expand(Session.Fields.User, (user) => user.select(User.Fields.Name));
  const sessions = await tm1.sessions.list(withUsers).toArray();
  return sessions.map((session) => `${session.ID} ${session.User?.Name} ${session.Context}`);
  // @end
}

export async function closeLabelled(tm1: Tm1Client): Promise<void> {
  // @start region = close
  // Close the sessions labelled "Nightly load", except this client's own
  const own = (await tm1.sessions.activeSession(ODataQuery.none().select(Session.Fields.ID))).ID;
  const labelled = ODataQuery.none().select(Session.Fields.ID).filter(Session.Fields.Context.eq("Nightly load"));
  for await (const session of tm1.sessions.list(labelled)) {
    if (session.ID !== own) await tm1.sessions.close(session.ID!);
  }
  // @end
}

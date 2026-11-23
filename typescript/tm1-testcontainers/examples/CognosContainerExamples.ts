import { ODataQuery, Tm1Authenticator, Tm1Client, User } from "@skipcheck/tm1-client";
import { CognosContainer, Tm1ServerContainer, type CognosSource, type Tm1Source } from "@skipcheck/tm1-testcontainers";

// Examples for CognosContainer, quoted in its documentation and Tm1ServerContainer's.

export async function camSecurity(cognosSource: CognosSource, tm1Source: Tm1Source): Promise<string | undefined> {
  // @start region = cam-security
  await using cognos = new CognosContainer(cognosSource).withUser("admin", "adminpw", "Admins").withUser("viewer", "viewerpw", "Readers");
  await using server = new Tm1ServerContainer(tm1Source).withCamSecurity(cognos, "admin");
  await server.start(); // starts Cognos first
  // Each test user logs in with their own identity, through the LDAP namespace
  await using viewer = Tm1Client.builder()
    .baseUrl(server.getRestUrl())
    .authenticator(Tm1Authenticator.camNamespace("viewer", "viewerpw", CognosContainer.NAMESPACE))
    .build();
  // TM1 knows the user by their CAMID
  const user = await viewer.users.activeUser(ODataQuery.none().select(User.Fields.Name));
  return user.Name;
  // @end
}

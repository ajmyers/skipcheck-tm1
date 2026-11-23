import { Tm1Authenticator, Tm1Client } from "@skipcheck/tm1-client";

// Examples for CognosPassports, quoted in its documentation.

export function logon(password: string): Tm1Client {
  // @start region = logon
  // The client logs on to Cognos for a passport at every TM1 login, so TM1 never sees the
  // password and an expired passport is never reused
  return Tm1Client.builder()
    .address("https://tm1.example.com:8010")
    .authenticator(Tm1Authenticator.cognosLogon("https://cognos.example.com:9300", "LDAP", "alice", password))
    .build();
  // @end
}

export function idToken(currentIdToken: () => Promise<string>): Tm1Client {
  // @start region = id-token
  // An application that signed its user in with OpenID Connect hands over their ID token;
  // Cognos's OIDC namespace turns it into a passport
  return Tm1Client.builder()
    .address("https://tm1.example.com:8010")
    .authenticator(Tm1Authenticator.cognosIdToken("https://cognos.example.com:9300", "OIDC", currentIdToken))
    .build();
  // @end
}

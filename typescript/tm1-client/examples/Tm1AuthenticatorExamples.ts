// SPDX-License-Identifier: Apache-2.0

import { Tm1Authenticator } from "@skipcheck/tm1-client";

// Examples for Tm1Authenticator, quoted in its documentation.

export function kinds(passports: () => Promise<string>): Tm1Authenticator[] {
  // @start region = kinds
  // TM1's own security
  const basic = Tm1Authenticator.basic("admin", "apple");
  // CAM, with a user and password
  const cam = Tm1Authenticator.camNamespace("alice", "secret", "LDAP");
  // CAM, with a passport
  const passport = Tm1Authenticator.camPassport(passports);
  // A passport from Cognos
  const cognos = Tm1Authenticator.cognosLogon("http://cognos:9300", "LDAP", "alice", "secret");
  // @end
  return [basic, cam, passport, cognos];
}

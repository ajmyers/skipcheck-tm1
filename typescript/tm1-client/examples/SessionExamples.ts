// SPDX-License-Identifier: Apache-2.0

import {
  CellService,
  MessageLogEntry,
  ODataPath,
  ODataQuery,
  SandboxCreate,
  SandboxService,
  Tm1Authenticator,
  Tm1Request,
  Tm1Session,
  type Tm1Stream,
} from "@skipcheck/tm1-client";

// Examples for Tm1Session, quoted in its documentation.

export function build(password: string): Tm1Session {
  // @start region = build
  // CAM security, with a label that shows in Sessions and Threads
  return Tm1Session.builder()
    .address("https://tm1.example.com:8010")
    .authenticator(Tm1Authenticator.camNamespace("alice", password, "LDAP"))
    .sessionContext("Sales planning")
    .build();
  // @end
}

export function passport(cognos: () => Promise<string>): Tm1Session {
  // @start region = passport
  // CAM passports expire, so the session asks for a current one on every login
  return Tm1Session.builder().address("https://tm1.example.com:8010").authenticator(Tm1Authenticator.camPassport(cognos)).build();
  // @end
}

export function request(session: Tm1Session): Tm1Stream<MessageLogEntry> {
  // @start region = request
  // GET MessageLogEntries, sent as a request of your own: the latest 20 entries
  const latest = ODataQuery.none().orderByDescending(MessageLogEntry.Fields.TimeStamp).top(20);
  return session.stream(Tm1Request.list(ODataPath.of("MessageLogEntries"), latest, MessageLogEntry));
  // @end
}

export async function sandbox(session: Tm1Session): Promise<void> {
  // @start region = sandbox
  // The cell: every dimension, in the cube's order
  const cell = { Region: "North", Month: "Jan" };
  await new SandboxService(session).create(SandboxCreate.builder().name("What-if").build());
  await new CellService(session.withSandbox("What-if")).update("Sales", cell, 1200);
  // @end
}

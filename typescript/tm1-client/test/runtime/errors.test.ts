// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from "vitest";
import {
  ODataError,
  Tm1ApiException,
  Tm1AuthenticationException,
  Tm1BadRequestException,
  Tm1ConflictException,
  Tm1ErrorClassifier,
  Tm1ForbiddenException,
  Tm1NotFoundException,
  Tm1NotImplementedException,
  Tm1PreconditionFailedException,
  Tm1ProtocolException,
  Tm1ServerException,
  Tm1TransportException,
  Tm1UnsupportedOperationException,
} from "../../src/index.js";

const error = (json: string): ODataError | undefined => ODataError.from(JSON.parse(json));
const msg = (message: string): string => `{"error":{"code":"278","message":"${message}"}}`;
const api = (status: number, body: string): Tm1ApiException =>
  Tm1ApiException.of(status, Tm1ErrorClassifier.defaults(), body === "" ? undefined : error(body), "GET", "Cubes('NoSuchCube')");

/** The Java client's `Tm1ErrorsTest`: reading TM1's errors and classifying them. */
describe("Tm1 errors", () => {
  it("reads the OData error body", () => {
    const e = error('{"error":{"code":"278","message":"Boom","target":"Name","details":[{"code":"1","message":"inner"}]}}')!;
    expect(e.code).toBe("278");
    expect(e.message).toBe("Boom");
    expect(e.target).toBe("Name");
    expect(e.details.map((d) => d.message)).toEqual(["inner"]);
    expect(ODataError.from(JSON.parse('{"value":[]}'))).toBeUndefined();
    expect(ODataError.from(JSON.parse("[]"))).toBeUndefined();
  });

  /** TM1 11.8 sends impersonation errors as plain text: a status line, then the message. */
  it("reads and classifies plain-text errors", () => {
    const unknown = ODataError.fromText("Error: Bad Request\r\nThe user specified in the impersonation request does not exist.\r\n")!;
    expect(unknown).toBeDefined();
    expect(unknown.message).toBe("The user specified in the impersonation request does not exist.");
    expect(unknown.code).toBe("");
    const classifier = Tm1ErrorClassifier.defaults();
    expect(classifier.classify(400, unknown)).toBe("UnknownUser");
    expect(classifier.classify(403, ODataError.fromText("Error: Forbidden\r\nYou have insufficient rights to impersonate the user specified!\r\n"))).toBe(
      "ImpersonationRefused",
    );
    expect(
      classifier.classify(403, ODataError.fromText("Error: Forbidden\r\nYou need to be a (data-)administrator to be able to impersonate another user.\r\n")),
    ).toBe("ImpersonationRefused");
    expect(ODataError.fromText("Error: Unauthorized\r\n")).toBeUndefined();
    expect(ODataError.fromText("x".repeat(5000))!.message).toHaveLength(1001);
  });

  it("classifies captured responses", () => {
    // GET Cubes('NoSuchCube')
    const notFound = api(404, `{"error":{"code":"278","message":"'NoSuchCube' can not be found in collection of type 'Cube'."}}`);
    expect(notFound).toBeInstanceOf(Tm1NotFoundException);
    expect(notFound.kind).toBe("NotFound");
    expect(notFound.message).toBe("TM1 returned 404: 'NoSuchCube' can not be found in collection of type 'Cube'. [code 278] (GET Cubes('NoSuchCube'))");
    // $search
    expect(api(501, msg("$search not implemented."))).toBeInstanceOf(Tm1NotImplementedException);
    // PATCH with a stale If-Match
    expect(api(412, msg(""))).toBeInstanceOf(Tm1PreconditionFailedException);
    // Multipart $batch
    const multipart = api(400, msg("The content type specified is not supported. Please use JSON (application/json) as the content type."));
    expect(multipart).toBeInstanceOf(Tm1BadRequestException);
    expect(multipart.kind).toBe("Other");
  });

  it("classifies known messages", () => {
    expect(api(403, msg("You have insufficient rights to impersonate the user specified!")).kind).toBe("ImpersonationRefused");
    expect(api(400, msg("The user specified in the impersonation request does not exist")).kind).toBe("UnknownUser");
    expect(api(400, msg("TM1UserException: Cancel")).kind).toBe("Cancelled");
    expect(api(400, msg("DataSpreadFailed")).kind).toBe("DataSpreadFailed");
    expect(api(400, '{"error":{"code":"248","message":"Invalid element unique name specified in dimensional context"}}').kind).toBe("InvalidUniqueName");
    expect(api(400, msg("Dimension 'Region' already exists"))).toBeInstanceOf(Tm1ConflictException);
    expect(api(400, msg("ObjectNotFound"))).toBeInstanceOf(Tm1NotFoundException);
    expect(api(400, msg("Object is locked by another user")).kind).toBe("Locked");
    expect(api(400, msg("Subset is used by a view and cannot be deleted")).kind).toBe("InUse");
    // "Blocked" isn't "locked"
    expect(api(400, msg("Blocked")).kind).toBe("Other");
  });

  it("falls back on the status", () => {
    expect(api(401, "")).toBeInstanceOf(Tm1AuthenticationException);
    expect(api(403, "")).toBeInstanceOf(Tm1ForbiddenException);
    expect(api(409, "")).toBeInstanceOf(Tm1ConflictException);
    expect(api(428, "")).toBeInstanceOf(Tm1PreconditionFailedException);
    expect(api(503, "")).toBeInstanceOf(Tm1ServerException);
    expect(api(418, "")).toBeInstanceOf(Tm1BadRequestException);
    expect(Tm1ApiException.of(302, Tm1ErrorClassifier.defaults(), undefined, "GET", "x").constructor).toBe(Tm1ApiException);
  });

  it("lets callers add rules", () => {
    const custom = Tm1ErrorClassifier.defaults().withRule(Tm1ErrorClassifier.Rule.message("frozen", "Locked"));
    expect(custom.classify(400, error(msg("Cube is frozen")))).toBe("Locked");
    expect(Tm1ErrorClassifier.defaults().classify(400, error(msg("Cube is frozen")))).toBe("Other");
    expect(
      Tm1ErrorClassifier.defaults().withRule(Tm1ErrorClassifier.Rule.code("999", "InUse")).classify(400, error('{"error":{"code":"999","message":"x"}}')),
    ).toBe("InUse");
    expect(() => new Tm1ErrorClassifier.Rule(undefined, undefined, undefined, "Other")).toThrow(TypeError);
  });

  it("says whether a retry could help and whether the request may have applied", () => {
    expect(api(503, "").isRetryable()).toBe(true);
    expect(api(503, "").mayHaveApplied()).toBe(true);
    expect(api(400, "").isRetryable()).toBe(false);
    expect(api(400, "").mayHaveApplied()).toBe(false);
    expect(api(501, "").mayHaveApplied()).toBe(false);

    const connect = new Tm1TransportException("Connect", false, "POST", "x");
    expect(connect.isRetryable()).toBe(true);
    expect(connect.mayHaveApplied()).toBe(false);
    const postTimedOut = new Tm1TransportException("Response", false, "POST", "x");
    expect(postTimedOut.isRetryable()).toBe(false);
    expect(postTimedOut.mayHaveApplied()).toBe(true);
    expect(new Tm1TransportException("Response", true, "GET", "x").isRetryable()).toBe(true);

    const protocol = new Tm1ProtocolException("bad", undefined, "GET", "x");
    expect(protocol.isRetryable()).toBe(false);
    expect(protocol.mayHaveApplied()).toBe(true);

    const unsupported = new Tm1UnsupportedOperationException("no", "Cube.Reserve");
    expect(unsupported.isRetryable()).toBe(false);
    expect(unsupported.mayHaveApplied()).toBe(false);
  });

  it("reads an error body whatever its form", () => {
    expect(ODataError.parse(new TextEncoder().encode(msg("Boom")))!.message).toBe("Boom");
    expect(ODataError.parse("Error: Bad Request\r\nPlain words.\r\n")!.message).toBe("Plain words.");
    expect(ODataError.parse(new Uint8Array())).toBeUndefined();
    expect(Tm1ApiException.fromBody(404, msg("'x' can not be found"), "GET", "u", Tm1ErrorClassifier.defaults())).toBeInstanceOf(Tm1NotFoundException);
  });
});

import { Tm1Service, type Tm1Endpoints } from "../service.js";

/** Not yet ported from the Java client's `MemberService`: listed in coverage/pending-classes.txt. */
export class MemberService extends Tm1Service {
  static readonly endpoints: Tm1Endpoints<MemberService> = {};
}

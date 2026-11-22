import { Tm1Service, type Tm1Endpoints } from "../service.js";

/** Not yet ported from the Java client's `ContentService`: listed in coverage/pending-classes.txt. */
export class ContentService extends Tm1Service {
  static readonly endpoints: Tm1Endpoints<ContentService> = {};
}

import { Tm1Service, type Tm1Endpoints } from "../service.js";

/** Not yet ported from the Java client's `GitService`: listed in coverage/pending-classes.txt. */
export class GitService extends Tm1Service {
  static readonly endpoints: Tm1Endpoints<GitService> = {};
}

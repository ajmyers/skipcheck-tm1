import { Tm1Service, type Tm1Endpoints } from "../service.js";

/** Not yet ported from the Java client's `LevelService`: listed in coverage/pending-classes.txt. */
export class LevelService extends Tm1Service {
  static readonly endpoints: Tm1Endpoints<LevelService> = {};
}

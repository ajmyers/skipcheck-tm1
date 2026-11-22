import { Tm1Service, type Tm1Endpoints } from "../service.js";

/** Not yet ported from the Java client's `CellService`: listed in coverage/pending-classes.txt. */
export class CellService extends Tm1Service {
  static readonly endpoints: Tm1Endpoints<CellService> = {};
}

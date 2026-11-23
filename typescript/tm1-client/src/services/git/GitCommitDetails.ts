// SPDX-License-Identifier: Apache-2.0

import { required } from "../../util.js";

/** What a push's commit says, and who it's by: the message, the author's name and email address,
 * each required. */
export class GitCommitDetails {
  /** What a push's commit says, and who it's by. @param message the commit message @param author
   * the author's name @param email the author's email address @throws TypeError if one is missing */
  constructor(
    readonly message: string,
    readonly author: string,
    readonly email: string,
  ) {
    required(message, "message");
    required(author, "author");
    required(email, "email");
    Object.freeze(this);
  }

  /** Whether `other` has the same message, author and email. */
  equals(other: unknown): boolean {
    return other instanceof GitCommitDetails && other.message === this.message && other.author === this.author && other.email === this.email;
  }

  /** The message, author and email, for logs. */
  toString(): string {
    return `GitCommitDetails[message=${this.message}, author=${this.author}, email=${this.email}]`;
  }
}

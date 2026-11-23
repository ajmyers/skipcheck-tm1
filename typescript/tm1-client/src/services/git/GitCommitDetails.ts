import { required } from "../../util.js";

/** What a push's commit says, and who it's by: the message, the author's name and email address,
 * each required. */
export class GitCommitDetails {
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

  equals(other: unknown): boolean {
    return other instanceof GitCommitDetails && other.message === this.message && other.author === this.author && other.email === this.email;
  }

  toString(): string {
    return `GitCommitDetails[message=${this.message}, author=${this.author}, email=${this.email}]`;
  }
}

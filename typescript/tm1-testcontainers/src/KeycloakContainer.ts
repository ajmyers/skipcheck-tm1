import { GenericContainer } from "./GenericContainer.js";
import { IllegalStateException, Settings } from "./settings.js";

const ID_TOKEN = /"id_token"\s*:\s*"([^"]+)"/;
const TIMEOUT = 30_000;

/** Keycloak, the OpenID Connect provider behind a {@link CognosContainer}'s OIDC namespace, as the
 * Java `KeycloakContainer`: reached as `keycloak` on its network, with the `cognos` realm the
 * Cognos image imports and its `tm1-app` client. Its issuer is {@link getNetworkUrl} for Cognos
 * and the tests alike. */
export class KeycloakContainer extends GenericContainer {
  /** The Keycloak image. */
  static readonly DEFAULT_IMAGE = "quay.io/keycloak/keycloak:26.4";

  /** Overrides the Keycloak image. */
  static readonly IMAGE = "KEYCLOAK_IMAGE";

  /** Keycloak's HTTP port inside the container. */
  static readonly HTTP_PORT = 8080;

  /** Its name on the network. */
  static readonly NETWORK_ALIAS = "keycloak";

  /** The realm the Cognos test users are in. */
  static readonly REALM = "cognos";

  /** The client an app gets ID tokens with, by the password grant. */
  static readonly APP_CLIENT = "tm1-app";

  /** Keycloak's image is built for every platform, so it runs as the machine's own. */
  protected override platform(): string | undefined {
    return undefined;
  }

  /** Keycloak in `image`; {@link DEFAULT_IMAGE}, or `KEYCLOAK_IMAGE` if set, by default. */
  constructor(image: string = Settings.text(Settings.get(KeycloakContainer.IMAGE)) ?? KeycloakContainer.DEFAULT_IMAGE) {
    super(image);
    this.withCommand("start-dev");
    this.withExposedPorts(KeycloakContainer.HTTP_PORT);
    this.withNetworkAliases(KeycloakContainer.NETWORK_ALIAS);
    this.withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin");
    this.withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin");
    this.withEnv("KC_HTTP_ENABLED", "true");
    // One issuer for Cognos and the tests, whichever address they use
    this.withEnv("KC_HOSTNAME", this.getNetworkUrl());
    this.waitingForHttp("/realms/master", KeycloakContainer.HTTP_PORT, (s) => s === 200, 3 * 60_000);
  }

  /** Keycloak's address on its network, as Cognos and TM1 reach it. */
  getNetworkUrl(): string {
    return "http://" + KeycloakContainer.NETWORK_ALIAS + ":" + KeycloakContainer.HTTP_PORT;
  }

  /** Keycloak's address on the Docker host. */
  getUrl(): string {
    return "http://" + this.getHost() + ":" + this.getMappedPort(KeycloakContainer.HTTP_PORT);
  }

  /** An ID token for a test user, by the password grant of {@link APP_CLIENT}.
   * @throws IllegalStateException if Keycloak refused */
  async idToken(user: string, password: string): Promise<string> {
    const form = new URLSearchParams({ grant_type: "password", client_id: KeycloakContainer.APP_CLIENT, scope: "openid", username: user, password });
    const response = await fetch(this.getUrl() + "/realms/" + KeycloakContainer.REALM + "/protocol/openid-connect/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: form.toString(),
      signal: AbortSignal.timeout(TIMEOUT),
    });
    const body = await response.text();
    const m = ID_TOKEN.exec(body);
    if (response.status !== 200 || !m) throw new IllegalStateException(`Keycloak refused ${user} (HTTP ${response.status}): ${body}`);
    return m[1]!;
  }
}

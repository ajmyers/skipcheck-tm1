/** Why tests are skipped, word for word as the Java annotations' constants (`RequiresTm1`,
 * `RequiresCognos`, `RequiresTm1Web`, `RequiresPaw`). Here rather than in `./vitest.js`, so the
 * containers can use them without loading vitest. */
export const Messages = {
  TM1_NO_SOURCE:
    "No TM1 source configured. Set one of TM1_PRIVATE_IMAGE, TM1_HOME_DIR, TM1_INSTALL_VOLUME, TM1_BUNDLE_DIR or TM1_BUNDLE_URL (as an environment variable or system property) to run tests against TM1.",
  TM1_NO_DOCKER: "Docker isn't available, so there's no TM1 to test against",
  COGNOS_NO_SOURCE:
    "No Cognos source configured. Set one of COGNOS_PRIVATE_IMAGE, COGNOS_INSTALL_VOLUME, COGNOS_ZIP_DIR or COGNOS_ZIP_URL, or put a casrv-*-linuxi38664h.zip in TM1_BUNDLE_DIR (as an environment variable or system property) to run tests against Cognos.",
  TM1_WEB_NO_SOURCE:
    "No TM1 Web source configured. Set one of TM1_WEB_PRIVATE_IMAGE, TM1_WEB_HOME_DIR, TM1_WEB_INSTALL_VOLUME, TM1_WEB_BUNDLE_DIR or TM1_WEB_BUNDLE_URL, or put a tm1web bundle in TM1_BUNDLE_DIR (as an environment variable or system property) to run tests against TM1 Web.",
  PAW_NO_SOURCE:
    "No Planning Analytics Workspace source configured. Set PAW_ZIP to IBM's ipa_workspace_local_*.zip, or PAW_BUNDLE_DIR to its folder (as an environment variable or system property), to run tests against PAW.",
  PAW_NO_LICENSE:
    "IBM's Planning Analytics Workspace license hasn't been accepted. Read it (licenses/LA_en in the PAW Local zip), then set PAW_ACCEPT_LICENSE=true (as an environment variable or system property) to run tests against PAW.",
} as const;

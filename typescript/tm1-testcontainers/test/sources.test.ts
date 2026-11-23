import { readFileSync, utimesSync } from "node:fs";
import { join } from "node:path";
import { inspect } from "node:util";
import { describe, expect, it } from "vitest";
import { CognosSource, IllegalArgumentException, PawContainer, PawSource, Tm1ServerContainer, Tm1Source, Tm1WebSource } from "../src/index.js";
import { Repo, settings, useTempDir, without } from "./support/files.js";

/** Java's Tm1SourceTest. */
describe("Tm1Source", () => {
  const temp = useTempDir();
  const fakeInstall = (): string => {
    temp.touch(join("tm1_64", "bin64", "tm1s.exe"));
    return join(temp.path, "tm1_64");
  };

  it("finds nothing when nothing is set", () => {
    expect(Tm1Source.discover(settings({}))).toBeUndefined();
    expect(Tm1Source.discover(settings({ [Tm1Source.HOME_DIR]: "  " }))).toBeUndefined();
  });

  it("tries sources in the image's order", () => {
    const install = fakeInstall();
    const all = {
      [Tm1Source.PRIVATE_IMAGE]: "registry.example.com/private/tm1-server:2.1.19",
      [Tm1Source.HOME_DIR]: install,
      [Tm1Source.INSTALL_VOLUME]: "tm1_tm1-install",
      [Tm1Source.BUNDLE_DIR]: temp.path,
      [Tm1Source.BUNDLE_URL]: "https://example.com/b.tar.gz",
    };
    expect(Tm1Source.discover(settings(all))).toBeInstanceOf(Tm1Source.PrivateImage);
    expect(Tm1Source.discover(settings(without(all, Tm1Source.PRIVATE_IMAGE)))).toStrictEqual(new Tm1Source.InstallDir(install));
    expect(Tm1Source.discover(settings(without(all, Tm1Source.PRIVATE_IMAGE, Tm1Source.HOME_DIR)))).toStrictEqual(
      new Tm1Source.InstallVolume("tm1_tm1-install"),
    );
    expect(Tm1Source.discover(settings(without(all, Tm1Source.PRIVATE_IMAGE, Tm1Source.HOME_DIR, Tm1Source.INSTALL_VOLUME)))).toStrictEqual(
      new Tm1Source.Bundle(temp.path),
    );
    const url = Tm1Source.discover(settings({ [Tm1Source.BUNDLE_URL]: "https://example.com/b.tar.gz", [Tm1Source.BUNDLE_SHA256]: "abc" }));
    expect(url).toBeInstanceOf(Tm1Source.BundleUrl);
    expect((url as Tm1Source.BundleUrl).url.href).toBe("https://example.com/b.tar.gz");
    expect((url as Tm1Source.BundleUrl).sha256).toBe("abc");
  });

  it("trims settings and treats a blank file or SHA-256 as unset", () => {
    expect(Tm1Source.discover(settings({ [Tm1Source.PRIVATE_IMAGE]: "  me/tm1:1  " }))).toStrictEqual(new Tm1Source.PrivateImage("me/tm1:1"));
    expect(Tm1Source.discover(settings({ [Tm1Source.BUNDLE_DIR]: temp.path, [Tm1Source.BUNDLE]: " " }))).toStrictEqual(new Tm1Source.Bundle(temp.path));
    expect(new Tm1Source.BundleUrl("https://example.com/b.tar.gz", " ").sha256).toBeUndefined();
    expect(() => new Tm1Source.PrivateImage(" ")).toThrow("image must not be blank");
  });

  it("fails on a wrong setting rather than skipping", () => {
    expect(() => Tm1Source.discover(settings({ [Tm1Source.HOME_DIR]: temp.path }))).toThrow(IllegalArgumentException);
    expect(() => Tm1Source.discover(settings({ [Tm1Source.HOME_DIR]: temp.path }))).toThrow("no bin64/tm1s.exe");
    expect(() => Tm1Source.discover(settings({ [Tm1Source.BUNDLE_DIR]: join(temp.path, "missing") }))).toThrow("is not a folder");
    expect(() => Tm1Source.discover(settings({ [Tm1Source.BUNDLE_DIR]: temp.path, [Tm1Source.BUNDLE]: "nope.tar.gz" }))).toThrow("isn't in");
  });

  it("names a bundle file within its folder", () => {
    temp.touch("tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz");
    const found = Tm1Source.discover(
      settings({ [Tm1Source.BUNDLE_DIR]: temp.path, [Tm1Source.BUNDLE]: "tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz" }),
    );
    expect(found).toStrictEqual(new Tm1Source.Bundle(temp.path, "tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz"));
    expect(found?.describe()).toBe("bundle " + join(temp.path, "tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz"));
    expect(new Tm1Source.Bundle(temp.path).describe()).toBe("bundle " + temp.path + " (newest)");
  });

  it("never describes a URL's query string", () => {
    const url = new Tm1Source.BundleUrl("https://bucket.s3.amazonaws.com/b.tar.gz?X-Amz-Signature=secret");
    expect(url.describe()).toBe("bundle URL https://bucket.s3.amazonaws.com/b.tar.gz");
    // Nor does printing it: Java's record toString would
    expect(String(url)).not.toContain("secret");
    expect(inspect(url)).not.toContain("secret");
  });

  it("never describes credentials in a URL", () => {
    const url = new Tm1Source.BundleUrl(new URL("https://ci-user:s3cr3t@artifacts.example.com:8443/tm1/b.tar.gz"));
    expect(url.describe()).toBe("bundle URL https://artifacts.example.com:8443/tm1/b.tar.gz");
    expect(() => new Tm1Source.BundleUrl("not a url?sig=secret")).toThrow(/^Not a URL: not a url$/);
  });

  it("tags snapshots with edge", () => {
    expect(Tm1ServerContainer.imageTag("0.1.0-SNAPSHOT")).toBe("edge");
    expect(Tm1ServerContainer.imageTag("0.1.0-dev")).toBe("edge");
    expect(Tm1ServerContainer.imageTag("0.1.0-dev.1")).toBe("edge");
    expect(Tm1ServerContainer.imageTag("0.1.0-rc.1")).toBe("0.1.0-rc.1");
    expect(Tm1ServerContainer.imageTag("0.1.0")).toBe("0.1.0");
  });

  it("uses the edge tm1-server image unless TM1_SERVER_IMAGE says otherwise", () => {
    const saved = process.env[Tm1ServerContainer.SERVER_IMAGE];
    try {
      delete process.env[Tm1ServerContainer.SERVER_IMAGE];
      expect(new Tm1ServerContainer(new Tm1Source.InstallVolume("v")).getDockerImageName()).toBe("ghcr.io/ajmyers/tm1-server:edge");
      process.env[Tm1ServerContainer.SERVER_IMAGE] = " tm1-server:local ";
      expect(new Tm1ServerContainer(new Tm1Source.InstallVolume("v")).getDockerImageName()).toBe("tm1-server:local");
      // A private image is used as it is
      expect(new Tm1ServerContainer(new Tm1Source.PrivateImage("me/tm1:11.8")).getDockerImageName()).toBe("me/tm1:11.8");
    } finally {
      if (saved === undefined) delete process.env[Tm1ServerContainer.SERVER_IMAGE];
      else process.env[Tm1ServerContainer.SERVER_IMAGE] = saved;
    }
  });

  it("mounts each source as Java does", () => {
    temp.touch("b.tar.gz");
    const bundle = new Tm1ServerContainer(new Tm1Source.Bundle(temp.path, "b.tar.gz"));
    expect(bundle.getBinds()).toEqual([`${temp.path}:/ibm:ro`, "tm1-testcontainers-cache:/tm1/cache"]);
    expect(bundle.getEnvMap().get("TM1_BUNDLE")).toBe("b.tar.gz");
    expect(bundle.getEnvMap().get("TM1_SEED")).toBe("empty");
    expect(bundle.getExposedPorts()).toEqual([8010]);
    expect(bundle.getUsername()).toBe("admin");
    expect(bundle.getPassword()).toBe("");
    const install = new Tm1ServerContainer(new Tm1Source.InstallDir(fakeInstall()));
    expect(install.getBinds()).toEqual([`${join(temp.path, "tm1_64")}:/opt/ibm/cognos/tm1_64:ro`]);
    expect(new Tm1ServerContainer(new Tm1Source.InstallVolume("v")).getBinds()).toEqual(["v:/opt/ibm/cognos/tm1_64"]);
    // The URL as given, query and all: the image needs the signature
    const url = new Tm1ServerContainer(new Tm1Source.BundleUrl("https://x/b.tar.gz?sig=s'1", "ab"));
    expect(url.getEnvMap().get("TM1_BUNDLE_URL")).toBe("https://x/b.tar.gz?sig=s'1");
    expect(url.getEnvMap().get("TM1_BUNDLE_SHA256")).toBe("ab");
    expect(url.getBinds()).toEqual(["tm1-testcontainers-cache:/tm1/cache"]);
    const image = new Tm1ServerContainer(new Tm1Source.PrivateImage("me/tm1:11.8"));
    expect(image.getBinds()).toEqual([]);
    expect(image.getSource()).toStrictEqual(new Tm1Source.PrivateImage("me/tm1:11.8"));
    expect(() => image.withData(join(temp.path, "missing"))).toThrow("Not a folder");
    expect(() => image.getTm1Version()).toThrow("isn't started yet");
    expect(() => image.getRestUrl()).toThrow("isn't started yet");
  });
});

/** Java's CognosSourceTest. */
describe("CognosSource", () => {
  const temp = useTempDir();

  it("finds nothing when nothing is set", () => {
    expect(CognosSource.discover(settings({}))).toBeUndefined();
    expect(CognosSource.discover(settings({ [CognosSource.ZIP_DIR]: "  " }))).toBeUndefined();
  });

  it("tries sources in order", () => {
    expect(CognosSource.discover(settings({ [CognosSource.PRIVATE_IMAGE]: "cognos-server:12.1.1-private", [CognosSource.INSTALL_VOLUME]: "v" }))).toStrictEqual(
      new CognosSource.PrivateImage("cognos-server:12.1.1-private"),
    );
    expect(CognosSource.discover(settings({ [CognosSource.INSTALL_VOLUME]: "v", [CognosSource.ZIP_DIR]: temp.path }))).toStrictEqual(
      new CognosSource.InstallVolume("v"),
    );
    expect(CognosSource.discover(settings({ [CognosSource.ZIP_DIR]: temp.path, [CognosSource.ZIP_URL]: "https://example.com/c.zip" }))).toStrictEqual(
      new CognosSource.Zip(temp.path),
    );
    const url = CognosSource.discover(
      settings({ [CognosSource.ZIP_URL]: "https://example.com/c.zip", [CognosSource.ZIP_SHA256]: "abc" }),
    ) as CognosSource.ZipUrl;
    expect(url).toBeInstanceOf(CognosSource.ZipUrl);
    expect([url.url.href, url.sha256]).toEqual(["https://example.com/c.zip", "abc"]);
  });

  it("uses the TM1 bundle folder when it holds Cognos", () => {
    expect(CognosSource.discover(settings({ TM1_BUNDLE_DIR: temp.path }))).toBeUndefined();
    temp.touch("casrv-12.1.1-2512181837-linuxi38664h.zip");
    expect(CognosSource.discover(settings({ TM1_BUNDLE_DIR: temp.path }))).toStrictEqual(new CognosSource.Zip(temp.path));
    expect(CognosSource.discover(settings({ TM1_BUNDLE_DIR: temp.path, [CognosSource.ZIP]: "casrv-12.1.1-2512181837-linuxi38664h.zip" }))).toStrictEqual(
      new CognosSource.Zip(temp.path, "casrv-12.1.1-2512181837-linuxi38664h.zip"),
    );
  });

  it("fails on a wrong setting rather than skipping", () => {
    expect(() => CognosSource.discover(settings({ [CognosSource.ZIP_DIR]: join(temp.path, "missing") }))).toThrow(CognosSource.ZIP_DIR);
    expect(() => CognosSource.discover(settings({ [CognosSource.ZIP_DIR]: temp.path, [CognosSource.ZIP]: "nope.zip" }))).toThrow("nope.zip");
  });

  it("never describes a URL's credentials", () => {
    const source = new CognosSource.ZipUrl("https://user:secret@example.com:8443/c.zip?X-Amz-Signature=SECRET");
    expect(source.describe()).toBe("zip URL https://example.com:8443/c.zip");
    expect(inspect(source)).not.toContain("SECRET");
  });
});

/** Java's PawSourceTest. */
describe("PawSource", () => {
  const temp = useTempDir();

  it("finds nothing when nothing is set", () => {
    expect(PawSource.discover(settings({}))).toBeUndefined();
    expect(PawSource.discover(settings({ [PawSource.ZIP]: "  ", [PawSource.BUNDLE_DIR]: "" }))).toBeUndefined();
  });

  it("uses the zip set directly", () => {
    const zip = temp.touch("ipa_workspace_local_2.1.19.1260.zip");
    expect(PawSource.discover(settings({ [PawSource.ZIP]: zip }))).toStrictEqual(new PawSource(zip));
  });

  it("picks the newest zip in the bundle folder", () => {
    const older = temp.touch("ipa_workspace_local_2.1.18.1000.zip");
    const newer = temp.touch("ipa_workspace_local_2.1.19.1260.zip");
    temp.touch("something-else.zip");
    utimesSync(older, 1, 1);
    utimesSync(newer, 2, 2);
    expect(PawSource.discover(settings({ [PawSource.BUNDLE_DIR]: temp.path }))).toStrictEqual(new PawSource(newer));
  });

  it("prefers the zip over the bundle folder", () => {
    const zip = temp.touch("ipa_workspace_local_2.1.19.1260.zip");
    expect(PawSource.discover(settings({ [PawSource.ZIP]: zip, [PawSource.BUNDLE_DIR]: "/nowhere" }))).toStrictEqual(new PawSource(zip));
  });

  it("fails on a wrong setting rather than skipping", () => {
    expect(() => PawSource.discover(settings({ [PawSource.ZIP]: join(temp.path, "missing.zip") }))).toThrow("Not a file");
    expect(() => PawSource.discover(settings({ [PawSource.BUNDLE_DIR]: temp.path }))).toThrow("has no ipa_workspace_local_*.zip");
    expect(() => PawSource.discover(settings({ [PawSource.BUNDLE_DIR]: join(temp.path, "nope") }))).toThrow("is not a folder");
  });

  it("refuses to start without the license", async () => {
    const paw = new PawContainer(new PawSource(temp.touch("ipa_workspace_local_2.1.19.1260.zip")));
    if (!PawContainer.licenseAcceptedBySetting()) {
      await expect(paw.start()).rejects.toThrow(PawContainer.ACCEPT_LICENSE);
    }
    expect(() => paw.withSetting("TM1_HOME_DIR", "x")).toThrow(IllegalArgumentException);
    expect(paw.isRunning()).toBe(false);
    expect(() => paw.getUrl()).toThrow("PAW isn't started");
  });

  it("ships paw-local.sh as docker/paw has it", () => {
    expect(PawContainer.script().equals(readFileSync(Repo.pawScript))).toBe(true);
  });
});

/** Java's Tm1WebSourceTest. */
describe("Tm1WebSource", () => {
  const temp = useTempDir();
  const fakeInstall = (): string => {
    temp.touch(join("tm1web", "wlp", "bin", "server"));
    return join(temp.path, "tm1web");
  };

  it("finds nothing when nothing is set", () => {
    expect(Tm1WebSource.discover(settings({}))).toBeUndefined();
    expect(Tm1WebSource.discover(settings({ [Tm1WebSource.HOME_DIR]: "  " }))).toBeUndefined();
  });

  it("tries sources in the image's order", () => {
    const install = fakeInstall();
    expect(
      Tm1WebSource.discover(settings({ [Tm1WebSource.PRIVATE_IMAGE]: "registry.example.com/private/tm1-web:2.1.19", [Tm1WebSource.HOME_DIR]: install })),
    ).toBeInstanceOf(Tm1WebSource.PrivateImage);
    expect(Tm1WebSource.discover(settings({ [Tm1WebSource.HOME_DIR]: install, [Tm1WebSource.INSTALL_VOLUME]: "v" }))).toStrictEqual(
      new Tm1WebSource.InstallDir(install),
    );
    expect(Tm1WebSource.discover(settings({ [Tm1WebSource.INSTALL_VOLUME]: "v", [Tm1WebSource.BUNDLE_DIR]: temp.path }))).toStrictEqual(
      new Tm1WebSource.InstallVolume("v"),
    );
    expect(Tm1WebSource.discover(settings({ [Tm1WebSource.BUNDLE_DIR]: temp.path, [Tm1WebSource.BUNDLE_URL]: "https://example.com/b.tar.gz" }))).toStrictEqual(
      new Tm1WebSource.Bundle(temp.path),
    );
    const url = Tm1WebSource.discover(
      settings({ [Tm1WebSource.BUNDLE_URL]: "https://example.com/b.tar.gz", [Tm1WebSource.BUNDLE_SHA256]: "abc" }),
    ) as Tm1WebSource.BundleUrl;
    expect([url.url.href, url.sha256]).toEqual(["https://example.com/b.tar.gz", "abc"]);
  });

  it("uses the TM1 bundle folder when it holds TM1 Web", () => {
    expect(Tm1WebSource.discover(settings({ TM1_BUNDLE_DIR: temp.path }))).toBeUndefined();
    temp.touch("tm1web-11.0.112-26031316-2.1.19-linuxi38664h_bundle.tar.gz");
    expect(Tm1WebSource.discover(settings({ TM1_BUNDLE_DIR: temp.path }))).toStrictEqual(new Tm1WebSource.Bundle(temp.path));
    expect(
      Tm1WebSource.discover(settings({ TM1_BUNDLE_DIR: temp.path, [Tm1WebSource.BUNDLE]: "tm1web-11.0.112-26031316-2.1.19-linuxi38664h_bundle.tar.gz" })),
    ).toStrictEqual(new Tm1WebSource.Bundle(temp.path, "tm1web-11.0.112-26031316-2.1.19-linuxi38664h_bundle.tar.gz"));
    const other = temp.mkdir("other");
    expect(Tm1WebSource.discover(settings({ TM1_BUNDLE_DIR: temp.path, [Tm1WebSource.BUNDLE_DIR]: other }))).toStrictEqual(new Tm1WebSource.Bundle(other));
  });

  it("fails on a wrong setting rather than skipping", () => {
    expect(() => Tm1WebSource.discover(settings({ [Tm1WebSource.HOME_DIR]: temp.path }))).toThrow("no wlp/bin/server");
    expect(() => Tm1WebSource.discover(settings({ [Tm1WebSource.BUNDLE_DIR]: join(temp.path, "missing") }))).toThrow("is not a folder");
    expect(() => Tm1WebSource.discover(settings({ [Tm1WebSource.BUNDLE_DIR]: temp.path, [Tm1WebSource.BUNDLE]: "nope.tar.gz" }))).toThrow("isn't in");
  });

  it("never describes a URL with its credentials", () => {
    const source = new Tm1WebSource.BundleUrl("https://user:token@example.com:8443/b/tm1web.tar.gz?X-Amz-Signature=SECRET");
    expect(source.describe()).toBe("bundle URL https://example.com:8443/b/tm1web.tar.gz");
  });
});

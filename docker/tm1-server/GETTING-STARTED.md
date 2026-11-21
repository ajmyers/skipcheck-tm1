# Getting started with tm1-server

This guide takes you from nothing to a running IBM Planning Analytics (TM1) server
on your own computer, on **Mac, Windows or Linux**. It takes about 15 minutes, most
of it installing Docker.

You make three choices along the way. Pick the option that fits; you can change
any of them later:

- [**Step 2: Published image or the source code?**](#step-2-get-tm1-server) Most people use the published image.
- [**Step 3: Where does TM1 come from?**](#step-3-where-does-tm1-come-from) An IBM download file, cloud storage, an existing install, or a ready-made image.
- [**Step 4: Where should your files live?**](#step-4-where-should-your-files-live) A folder on your computer, an existing server's database, somewhere else, or nowhere (throwaway).

## The fast way: the setup script

Once Docker is installed ([Step 1](#step-1-install-docker)), one command does Steps 2
to 5 for you. It asks the same questions as this guide, checks your computer as it
goes (Docker, Rosetta on Apple Silicon, WSL 2 on Windows, free ports, the right
bundle), starts TM1 and waits until it's ready:

```sh
# Mac / Linux (Terminal)
curl -fsSL https://ajmyers.net/tm1/setup.sh | sh
```
```powershell
# Windows (PowerShell)
irm https://ajmyers.net/tm1/setup.ps1 | iex
```

To skip the questions, give the answers as options. For example, to use the bundle in
your Downloads folder and also start the SData sample:

```sh
# Mac / Linux
curl -fsSL https://ajmyers.net/tm1/setup.sh | sh -s -- --bundle ~/Downloads/tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz --samples
```
```powershell
# Windows
& ([scriptblock]::Create((irm https://ajmyers.net/tm1/setup.ps1))) -Bundle "$HOME\Downloads\tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz" -Samples
```

Add `--help` (`-Help` on Windows) to see every option. Without a terminal (in CI, or with
`--yes`) it never asks: a missing answer is an error naming the option to add. Running
it again is safe: it keeps your settings and the installed TM1, and says what it reused.

The rest of this guide does the same steps by hand, and explains each one.

> **Before you start:** this project contains **no IBM software**. You need your
> own licensed copy of IBM Planning Analytics TM1 Server, and you're responsible
> for following your IBM license terms.

**Contents:**
[1. Install Docker](#step-1-install-docker) ·
[2. Get tm1-server](#step-2-get-tm1-server) ·
[3. TM1 source](#step-3-where-does-tm1-come-from) ·
[4. Your files](#step-4-where-should-your-files-live) ·
[5. Start TM1](#step-5-start-tm1) ·
[6. Connect](#step-6-connect-to-tm1) ·
[Everyday commands](#everyday-commands) ·
[Troubleshooting](#troubleshooting) ·
[Removing everything](#removing-everything)

---

## Step 1: Install Docker

Docker runs TM1 in an isolated Linux environment called a *container*. TM1 Server
only exists for Linux on Intel/AMD (x86-64) processors, and Docker handles that on
every platform.

You need about **4 GB of free memory** and **2 GB of disk space**.

<details>
<summary><b>Mac</b></summary>

1. Check your Mac's processor: **Apple menu → About This Mac**. "Chip: Apple M…" is
   Apple Silicon; "Processor: Intel…" is Intel.
2. Download and install **Docker Desktop** from
   <https://www.docker.com/products/docker-desktop/>. Pick the Apple Silicon or Intel
   version to match.
3. Open Docker Desktop and accept the terms. Wait until it says the engine is running.
4. **Apple Silicon only:** TM1 needs Apple's Rosetta translation layer.
   1. Open **Terminal** (Applications → Utilities) and run:
      ```sh
      softwareupdate --install-rosetta --agree-to-license
      ```
   2. In Docker Desktop, open **Settings → General**, turn on
      **Use Rosetta for x86_64/amd64 emulation on Apple Silicon**, and click
      **Apply & restart**.

You'll type the commands in the rest of this guide into **Terminal**.
</details>

<details>
<summary><b>Windows</b></summary>

1. Use Windows 10 (22H2) or Windows 11, on an Intel or AMD PC. (Windows on ARM,
   such as Snapdragon laptops, also works but runs TM1 more slowly, through
   emulation.)
2. Download and install **Docker Desktop** from
   <https://www.docker.com/products/docker-desktop/>. Keep **Use WSL 2** selected
   during setup. Restart if asked.
3. Open Docker Desktop and accept the terms. Wait until it says the engine is running.
4. Only if you'll work from the source code (Step 2, choice **S**): install
   **Git for Windows** from <https://git-scm.com/download/win>.

You'll type the commands in the rest of this guide into **PowerShell** (Start menu →
"PowerShell"). Where Mac/Linux and Windows commands differ, both are shown.
</details>

<details>
<summary><b>Linux</b></summary>

1. Install **Docker Engine** and the **Compose plugin** for your distribution, by
   following <https://docs.docker.com/engine/install/>.
2. Let your user run Docker without `sudo`, then log out and back in:
   ```sh
   sudo usermod -aG docker "$USER"
   ```
3. Check it works: `docker run --rm hello-world`.
4. **ARM machines only** (e.g. Raspberry Pi, Ampere servers): enable x86-64
   emulation. It's slow, so prefer an x86-64 machine:
   ```sh
   docker run --privileged --rm tonistiigi/binfmt --install amd64
   ```

Docker Desktop for Linux also works, if you'd rather have the same app as on Mac and
Windows.
</details>

**Check:** `docker compose version` prints a version number.

---

## Step 2: Get tm1-server

**Pick one.** If you're not sure, choose **R**.

| | Choice | Best for |
|---|---|---|
| [**R**](#r-use-the-published-image-recommended) | **Use the published image** (recommended): download two small files, nothing to build | Everyone who just wants a TM1 server |
| [**S**](#s-work-from-the-source-code) | **Work from the source code**: clone the repository and build the image yourself | Contributors, and anyone changing the image |

### R. Use the published image (recommended)

Make a folder for your TM1 server and download the compose file and example
settings into it:

```sh
# Mac / Linux
mkdir tm1 && cd tm1
curl -fsSLO https://raw.githubusercontent.com/ajmyers/tm1-pa-developer-tools/master/docker/tm1-server/compose.yaml
curl -fsSLO https://raw.githubusercontent.com/ajmyers/tm1-pa-developer-tools/master/docker/tm1-server/.env.example
mkdir bundles
```
```powershell
# Windows
mkdir tm1; cd tm1
$base = "https://raw.githubusercontent.com/ajmyers/tm1-pa-developer-tools/master/docker/tm1-server"
Invoke-WebRequest "$base/compose.yaml" -OutFile compose.yaml
Invoke-WebRequest "$base/.env.example" -OutFile .env.example
mkdir bundles
```

Docker downloads the image itself the first time you start TM1. Every command
from here on runs in this `tm1` folder.

→ Continue to [Step 3](#step-3-where-does-tm1-come-from).

### S. Work from the source code

Clone the repository, build the image, and tell compose to use your build:

```sh
# Mac / Linux
git clone https://github.com/ajmyers/tm1-pa-developer-tools.git
cd tm1-pa-developer-tools/docker/tm1-server
docker build --platform linux/amd64 -t tm1-server:local .
echo "TM1_SERVER_IMAGE=tm1-server:local" >> .env
```
```powershell
# Windows
git clone https://github.com/ajmyers/tm1-pa-developer-tools.git
cd tm1-pa-developer-tools\docker\tm1-server
docker build --platform linux/amd64 -t tm1-server:local .
Add-Content .env "TM1_SERVER_IMAGE=tm1-server:local"
```

The build takes about a minute. Always include `--platform linux/amd64`; see
[Troubleshooting](#troubleshooting). Every command from here on runs in this
`docker/tm1-server` folder.

→ Continue to [Step 3](#step-3-where-does-tm1-come-from).

---

## Step 3: Where does TM1 come from?

**Pick one.** If you're not sure, choose **A**.

| | You have... | Best for |
|---|---|---|
| [**A**](#a-the-ibm-download-file-recommended) | The IBM download file (`tm1server-...-linuxi38664h_bundle.tar.gz`) | Most people |
| [**B**](#b-the-ibm-download-in-cloud-storage) | That file in cloud storage (S3, R2, Azure, GCS, a web server) | Teams, CI |
| [**C**](#c-an-installed-tm1-folder) | An installed `tm1_64` folder, copied from a Linux TM1 server | Matching an existing server exactly |
| [**D**](#d-a-private-image-with-tm1-built-in) | A private Docker image someone built with TM1 inside | Teams and CI at scale |

> **Getting the file from IBM:** download **IBM Planning Analytics TM1 Server for
> Linux** from IBM Passport Advantage or Fix Central (depending on your
> entitlement). It's named `tm1server-<version>-linuxi38664h_bundle.tar.gz`, about
> 500 MB. Get the **Linux** file even if you're on Mac or Windows: it's what runs
> inside the container.

### A. The IBM download file (recommended)

Copy the file into the `bundles` folder:

```sh
# Mac / Linux
cp ~/Downloads/tm1server-*_bundle.tar.gz bundles/
```
```powershell
# Windows
Copy-Item $HOME\Downloads\tm1server-*_bundle.tar.gz bundles\
```

That's all. TM1 is installed from it once, on first start (about 15 seconds), into a
Docker volume, and reused after that. If you have several versions in `bundles`, the
newest is used; to pick one, see [Changing settings](#changing-settings) (`TM1_BUNDLE`).

**Prefer to keep the file where it is?** Instead of copying it, point to its folder
in a `.env` file (see [Changing settings](#changing-settings)):
```
TM1_BUNDLE_DIR=/Users/you/Downloads
```
On Windows, write paths with forward slashes: `TM1_BUNDLE_DIR=C:/Users/you/Downloads`.

→ Continue to [Step 4](#step-4-where-should-your-files-live).

### B. The IBM download in cloud storage

Two ways. Both keep your cloud credentials out of the container.

**B1. A download link.** Create a link the container can download from, such as a
**pre-signed URL** (`aws s3 presign`, R2/GCS signed URLs, an Azure SAS link) or any
private web server. Put it in a `.env` file (see [Changing settings](#changing-settings)):
```
TM1_BUNDLE_URL=https://my-bucket.s3.amazonaws.com/tm1server-2.1.19-...?X-Amz-...
# optional, but recommended: the file's SHA-256, checked before installing
TM1_BUNDLE_SHA256=6b476618ff6d...
```
TM1 is downloaded and installed on first start. Logs show the link without its
query string, so the credentials in a pre-signed link aren't printed.

**B2. Stream it in with your own cloud tools.** Use any command that writes the file
to its output, and pipe it into the installer. This only needs doing once per TM1
version, before the first start:
```sh
aws s3 cp s3://my-bucket/tm1server-2.1.19-...-linuxi38664h_bundle.tar.gz - \
  | docker compose run --rm -T --no-deps tm1 install -
```
It works the same with `gsutil cat gs://...`, `az storage blob download --file /dev/stdout ...`,
`curl -fsSL https://...`, `cat file.tar.gz`, and so on.

→ Continue to [Step 4](#step-4-where-should-your-files-live).

### C. An installed TM1 folder

If you have a TM1 Server installed on **Linux** (usually `/opt/ibm/cognos/tm1_64`),
copy that whole folder to your computer and point to it in a `.env` file (see
[Changing settings](#changing-settings)):
```
TM1_HOME_DIR=/Users/you/ibm/tm1_64
```
The folder must contain `bin64/tm1s.exe`. It's used as-is; nothing is installed.

→ Continue to [Step 4](#step-4-where-should-your-files-live).

### D. A private image with TM1 built in

Someone on your team builds an image with TM1 inside, once, and pushes it to your
**private** registry. See [`examples/private-image`](examples/private-image/Dockerfile).
Never push it anywhere public: it contains IBM software. Then point to it in a
`.env` file:
```
TM1_SERVER_IMAGE=registry.example.com/private/tm1-server:2.1.19
```
Log in to your registry first (`docker login registry.example.com`).

→ Continue to [Step 4](#step-4-where-should-your-files-live).

---

## Step 4: Where should your files live?

Each TM1 server has three kinds of files: its **config** (`tm1s.cfg`), its
**database** (cubes, dimensions, processes) and its **logs**. **Pick one:**

| | Choice | What happens |
|---|---|---|
| **1** | **In this project's `tm1-files` folder** (default) | Created for you on first start. Easy to look at and edit. Kept until you delete them. |
| **2** | **Start from an existing server's config or database** | You copy them in before the first start. |
| **3** | **Somewhere else on your computer** | Same as 1, in a folder you choose. |
| **4** | **Nowhere: a throwaway server** | Everything is deleted when the container is removed. |

### 1. In the `tm1-files` folder (default)

Nothing to do. After the first start you'll find:
```
tm1-files/
  tm1/       config/  data/  logs/     ← the "tm1" server (empty database)
  sdata/     config/  data/  logs/     ← IBM's "SData" sample, if you start it
  admin/     logs/                     ← the Admin Server
```
The `config/tm1s.cfg` created for you is a sensible default (plain HTTP, sandboxes
enabled, TI debugging on). Edit it and restart to change settings.

### 2. Start from an existing server's config or database

Before the first start, copy them into place:
```
tm1-files/tm1/config/tm1s.cfg        ← your tm1s.cfg (and tm1s-log.properties, if you have one)
tm1-files/tm1/data/                  ← your database files (*.cub, *.dim, *.pro, ...)
```
Your `tm1s.cfg` is used as-is, except for the settings the container depends on:
paths, ports and the Admin Server host. Those are adjusted automatically, and every
change is printed in the logs. Settings that can't work in the container (such as
Cognos/CAM security) are left alone, with a warning. The
[README](README.md#configuration) lists exactly which settings are adjusted.

### 3. Somewhere else on your computer

Set the folder in a `.env` file (see [Changing settings](#changing-settings)). The
same `tm1/`, `sdata/` and `admin/` folders are created inside it:
```
TM1_FILES_DIR=/Users/you/tm1-files
```

### 4. A throwaway server

Use `docker run` instead of compose, without mounting any files. This example
installs TM1 from your `bundles` folder (choice A):
```sh
# Mac / Linux
docker run --rm -p 8010:8010 -v "$PWD/bundles:/ibm:ro" ghcr.io/ajmyers/tm1-server
```
```powershell
# Windows
docker run --rm -p 8010:8010 -v "${PWD}\bundles:/ibm:ro" ghcr.io/ajmyers/tm1-server
```
If you chose **S** in Step 2, use `tm1-server:local` in place of
`ghcr.io/ajmyers/tm1-server`. Press **Ctrl+C** to stop it, and everything is gone.
(This is what automated tests do.)

---

## Step 5: Start TM1

```sh
docker compose up -d
```

The first time, this downloads the image (with choice R) and installs TM1 from your
bundle (about 15 seconds). Check that it's ready:

```sh
docker compose ps
```

Wait until **`tm1`** and **`tm1-admin`** both show **`(healthy)`**. Run the command
again if they still say `starting`.

**Want IBM's sample database too?** Start the SData sample alongside it:
```sh
docker compose --profile samples up -d
```

---

## Step 6: Connect to TM1

| Server | REST API address | Login |
|---|---|---|
| `tm1` (empty database) | `http://localhost:8010/api/v1/` | user `admin`, **no password** |
| `SData` (sample) | `http://localhost:8011/api/v1/` | user `admin`, password `apple` |
| Admin Server | `http://localhost:5895/api/v1/Servers` | none |

**Quick check:** open <http://localhost:8010/api/v1/$metadata> in your browser. You
should see a long XML document describing TM1's REST API.

**Ask TM1 something:**
```sh
# Mac / Linux
curl -u admin: 'http://localhost:8010/api/v1/Configuration/ProductVersion/$value'
```
```powershell
# Windows: use curl.exe, not curl
curl.exe -u admin: "http://localhost:8010/api/v1/Configuration/ProductVersion/`$value"
```
It prints the TM1 version, e.g. `11.8.03500.6`.

**From your tools and code**, connect with:

- host: `localhost`
- port: `8010` (`8011` for SData)
- SSL: **off** (plain HTTP)
- user: `admin`, with a blank password

You're done. 🎉

---

## Everyday commands

Run these in the `docker/tm1-server` folder.

| To... | Run |
|---|---|
| See what's running | `docker compose ps` |
| Watch the TM1 log | `docker compose logs -f tm1` (Ctrl+C to stop watching) |
| Stop (TM1 saves its data first) | `docker compose stop` |
| Start again | `docker compose start` (or `docker compose up -d`) |
| Restart after editing `tm1s.cfg` | `docker compose restart tm1` |
| Stop and remove the containers (your files are kept) | `docker compose down` |
| Start over with an empty database | `docker compose down`, delete `tm1-files/tm1`, then `docker compose up -d` |
| Update tm1-server (choice R) | `docker compose pull`, then `docker compose up -d` |
| Update tm1-server (choice S) | `git pull`, rebuild (`docker build --platform linux/amd64 -t tm1-server:local .`), then `docker compose up -d` |

TM1's own log files (`tm1server.log`, `tm1s.log`, login and event logs) are in
`tm1-files/tm1/logs`.

### Changing settings

Settings go in a file called **`.env`** in this folder, one `NAME=value` per line.
[`.env.example`](.env.example) lists every setting with an explanation.

- **No `.env` yet?** Start from the example, then uncomment (remove the `#` from) the
  lines you need:
  ```sh
  cp .env.example .env              # Mac / Linux
  ```
  ```powershell
  Copy-Item .env.example .env       # Windows
  ```
- **Already have one** (choice S in Step 2 creates it)? Open it in a text editor and
  add the lines you need. Don't copy the example over it, or you'll lose what's there.

Then run `docker compose up -d` to apply them.

### Changing the TM1 version

- **A (bundle file):** put the new file in `bundles` (or set `TM1_BUNDLE` to pick it), then
  remove the installed TM1 volume and start again:
  ```sh
  docker compose down
  docker volume ls              # find the one ending in _tm1-install
  docker volume rm tm1_tm1-install
  docker compose up -d
  ```
  The volume's name starts with your folder's name: `tm1_tm1-install` for choice R's
  `tm1` folder, `tm1-server_tm1-install` for choice S.
- **B (cloud):** update `TM1_BUNDLE_URL` (and `TM1_BUNDLE_SHA256`), or pipe the new file
  in (B2) after removing the volume as shown above.
- **C (folder):** point `TM1_HOME_DIR` at the new folder and run `docker compose up -d`.
- **D (image):** change `TM1_SERVER_IMAGE` and run `docker compose up -d`.

Your databases in `tm1-files` are kept across version changes.

---

## Troubleshooting

| What you see | What to do |
|---|---|
| `Cannot connect to the Docker daemon` / `docker: command not found` | Docker isn't running. Open Docker Desktop (Mac/Windows) and wait for it to start, or `sudo systemctl start docker` (Linux). |
| `ERROR: .../tm1s.exe not found ... does not include IBM software` | TM1 couldn't be found. Check your Step 3 choice: is the bundle file in `bundles`? Is `TM1_HOME_DIR` the folder that contains `bin64`? |
| `no bundle found` | The `bundles` folder (or `TM1_BUNDLE_DIR`) has no `tm1server-*_bundle.tar.gz`. Check the file name; it must be the **Linux** (`linuxi38664h`) download. |
| `download failed` / `checksum mismatch` | The `TM1_BUNDLE_URL` link expired or is wrong, or the file changed. Create a new link, or update `TM1_BUNDLE_SHA256`. |
| `port is already allocated` / `address already in use` | Something else is using port 8010, 8011 or 5895, perhaps another TM1. Stop it, or change the left-hand port numbers under `ports:` in `compose.yaml` (e.g. `"9010:8010"`). |
| `denied`, `pull access denied` or `manifest unknown` for `ghcr.io/ajmyers/tm1-server` | Docker couldn't download the image. Check your internet connection, and that your network allows `ghcr.io`. Or build it yourself (Step 2, choice **S**). |
| `pull access denied for tm1-server` | Compose is looking for your own build (`TM1_SERVER_IMAGE=tm1-server:local`) but it doesn't exist. Build it: `docker build --platform linux/amd64 -t tm1-server:local .` |
| `does not provide the specified platform (linux/amd64)` | The image was built without `--platform linux/amd64`. Rebuild with `docker build --platform linux/amd64 -t tm1-server:local .`. |
| Stuck at `(health: starting)` or `(unhealthy)` | Look at the log: `docker compose logs tm1`. Lines starting `[tm1-entrypoint]` explain what happened, including any `WARNING`s about your `tm1s.cfg`. |
| Very slow, or crashes immediately, on an Apple Silicon Mac | Rosetta isn't on. Redo Step 1, Mac step 4. |
| `bash\r: not found` on Windows | Windows line endings got into the files. Run `git config --global core.autocrlf input`, delete the folder, clone again, and rebuild. |
| Logins fail, with a warning about `IntegratedSecurityMode` | Your `tm1s.cfg` uses Cognos (CAM) security, which isn't available here. Set `IntegratedSecurityMode=1` to use TM1's own users. |
| Changes made in `tm1s.cfg` don't apply | Restart the server: `docker compose restart tm1`. |

Still stuck? Open an issue with the output of `docker compose logs tm1`.

---

## Removing everything

```sh
docker compose --profile samples down       # remove the containers
docker volume rm tm1_tm1-install            # remove the installed TM1 (choice R folder "tm1")
docker volume rm tm1-server_tm1-install     #   or, for choice S
docker image rm ghcr.io/ajmyers/tm1-server tm1-server:local   # remove the image(s)
```
Then delete the folder (`tm1` for choice R), or its `tm1-files` and `bundles`
contents, if you don't need them.

---

**Next:** the [README](README.md) covers everything in more depth: all configuration
options, running several TM1 servers, the Admin Server, ports and the `install`
command.

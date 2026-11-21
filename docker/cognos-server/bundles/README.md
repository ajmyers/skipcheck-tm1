# bundles/

Put your IBM Cognos Analytics repository zip here, e.g.

    casrv-12.1.1-2512181837-linuxi38664h.zip

Then run `docker compose up -d` from the folder above. It's installed once, on
first start. It's the file IBM's Cognos installer (`ca_instl`) reads: use the
**Linux** download (`linuxi38664h`), even on Mac or Windows. The installer
itself isn't needed.

Everything in this folder except this README is ignored by git, so your IBM
software is never committed.

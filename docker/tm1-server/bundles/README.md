# bundles/

Put your IBM TM1 Server bundle here, e.g.

    tm1server-2.1.19-26031712-linuxi38664h_bundle.tar.gz

For Planning Analytics 2.1.2 or 2.1.7, IBM's download is the full installer instead,
e.g. `tm1_linuxi38664h_2.1.7.1_ml.tar.gz`: put that here as it is.

Then run `docker compose up -d` from the folder above. It's installed once, on
first start. Use the **Linux** download (`linuxi38664h`), even on Mac or Windows.

Everything in this folder except this README is ignored by git, so your IBM
software is never committed.

# syntax=docker/dockerfile:1.7
# SPDX-License-Identifier: Apache-2.0

#
# A private tm1-server image with your licensed TM1 built in: no mounts, no install
# on startup. Good for CI and teams. Push it ONLY to a private registry (it contains
# IBM software, licensed to you).
#
# From the IBM bundle. Docker sends the WHOLE --build-context folder to the build,
# so point it at a folder holding just the bundle, not a downloads folder full of
# other IBM software:
#
#   docker build --platform linux/amd64 \
#     --build-context ibm=/path/to/ibm-downloads \
#     -t registry.example.com/private/tm1-server:2.1.19 .
#
# Optional: --build-arg BASE=<tag> to build on a different tm1-server version,
#           --build-arg TM1_BUNDLE=<file name> to pick a bundle in that folder.
ARG BASE=ghcr.io/ajmyers/tm1-server:latest
FROM ${BASE}
ARG TM1_BUNDLE=
RUN --mount=type=bind,from=ibm,target=/ibm tm1-install

# Or, from an already-installed tm1_64 folder instead of the bundle: replace the RUN
# line with the COPY below and pass --build-context tm1=/path/to/tm1_64
# COPY --from=tm1 . /opt/ibm/cognos/tm1_64

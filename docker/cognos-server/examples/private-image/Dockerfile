# syntax=docker/dockerfile:1.7
#
# A private cognos-server image with your licensed Cognos built in: no install on
# startup, and every container gets its own copy-on-write copy of the install, so
# containers can run in parallel (for tests). Push it ONLY to a private registry:
# it contains IBM software, licensed to you. It's about 10 GB.
#
# Docker sends the WHOLE --build-context folder to the build, so point it at a
# folder holding just the casrv-<version>-linuxi38664h.zip, not a downloads folder
# full of other IBM software:
#
#   docker build --platform linux/amd64 \
#     --build-context ibm=/path/to/folder-with-the-zip \
#     -t registry.example.com/private/cognos-server:12.1.1 .
#
# Optional: --build-arg BASE=<tag> to build on a different cognos-server version,
#           --build-arg COGNOS_ZIP=<file name> to pick a zip in that folder.
ARG BASE=ghcr.io/ajmyers/cognos-server:latest
FROM ${BASE}
ARG COGNOS_ZIP=
RUN --mount=type=bind,from=ibm,target=/ibm cognos-install

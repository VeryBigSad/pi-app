FROM --platform=$BUILDPLATFORM docker.io/library/golang@sha256:f85330846cde1e57ca9ec309382da3b8e6ae3ab943d2739500e08c86393a21b1 AS build
ARG TARGETOS
ARG TARGETARCH
WORKDIR /src
COPY relay/go.mod relay/go.sum ./
RUN go mod download
COPY relay/cmd ./cmd
COPY relay/internal ./internal
RUN CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -mod=readonly -trimpath -buildvcs=false -ldflags="-s -w -buildid=" -o /out/pi-mobile-relay ./cmd/relay

FROM docker.io/library/busybox:1.37.0-musl@sha256:fc6dddc4c44b1bfe37f41cae8e67d1693828e8f42a91862816d7953e2c9d3f23 AS tools

FROM gcr.io/distroless/static-debian13@sha256:f7f8f729987ad0fdf6b05eeeae94b26e6a0f613bdf46feea7fc40f7bd72953e6
COPY --from=build --chown=65532:65532 /out/pi-mobile-relay /usr/local/bin/pi-mobile-relay
COPY --from=tools --chown=65532:65532 /bin/busybox /usr/local/bin/wget
USER 65532:65532
STOPSIGNAL SIGTERM
ENTRYPOINT ["/usr/local/bin/pi-mobile-relay"]

# syntax=docker/dockerfile:1

FROM golang:1.26-bookworm AS build

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY cmd/ cmd/
COPY internal/ internal/

ARG TARGETOS=linux
ARG TARGETARCH=amd64
RUN CGO_ENABLED=0 GOOS=${TARGETOS} GOARCH=${TARGETARCH} go build \
    -trimpath \
    -ldflags="-s -w" \
    -o /out/finance-api \
    ./cmd/finance-api

FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=build /out/finance-api /finance-api

USER 65532:65532

# Listens on all interfaces inside the container; in a cluster only the identity router may reach it (NetworkPolicy),
# because it trusts the router's X-Nighthawk-Verified-User header. Never set DEV_VERIFIED_USER in an image or chart.
ENV LISTEN_ADDR=0.0.0.0:8091
EXPOSE 8091

ENTRYPOINT ["/finance-api"]

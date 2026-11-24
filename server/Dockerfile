FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG VERSION=dev
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w -X main.version=${VERSION}" -o /out/geocrew-server ./cmd/geocrew-server \
 && mkdir -p /out/data

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/geocrew-server /geocrew-server
COPY --from=build --chown=65532:65532 /out/data /data
ENV GEOCREW_DATA_DIR=/data
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD ["/geocrew-server", "-healthcheck", "localhost:8080"]
ENTRYPOINT ["/geocrew-server"]

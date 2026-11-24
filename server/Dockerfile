FROM golang:1.23-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/geocrew-server ./cmd/geocrew-server

FROM gcr.io/distroless/static-debian12
COPY --from=build /out/geocrew-server /geocrew-server
EXPOSE 8080
USER nonroot
ENTRYPOINT ["/geocrew-server"]

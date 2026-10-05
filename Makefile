.PHONY: run test check contract integration

run:
	go run ./cmd/finance-api

test:
	go test ./...

contract:
	go run ./cmd/contract > api/openapi.json

check:
	@test -z "$$(gofmt -l cmd internal)" || { gofmt -l cmd internal; exit 1; }
	go test ./...
	go vet ./...
	go build ./...
	@contract=$$(mktemp); trap 'rm -f "$$contract"' EXIT; go run ./cmd/contract > "$$contract" && diff -u api/openapi.json "$$contract"

integration:
	@test -n "$$TEST_DATABASE_URL" || { echo 'Set TEST_DATABASE_URL to a dedicated test database with timescaledb and vector enabled.'; exit 1; }
	go test ./... -count=1 -race -v

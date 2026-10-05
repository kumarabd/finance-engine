package engine

import (
	"errors"
	"testing"

	"github.com/jackc/pgx/v5/pgconn"
)

// Clients decide whether to retry, skip or surface an error from its code, so each database condition needs its own.
func TestPublicErrorCodes(t *testing.T) {
	for state, want := range map[string]string{
		"23505": "duplicate", // unique name or source identity
		"23503": "conflict",
		"40001": "busy", // serialization failure: retry with the same key
		"40P01": "busy", // deadlock
		"XX000": "internal",
	} {
		if got := PublicError(&pgconn.PgError{Code: state}).Code; got != want {
			t.Errorf("sqlstate %s: got %q, want %q", state, got, want)
		}
	}
	if got := PublicError(errors.New("boom")).Code; got != "internal" {
		t.Errorf("unknown error: got %q", got)
	}
}

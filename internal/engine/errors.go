package engine

import (
	"errors"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

type Error struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *Error) Error() string       { return e.Message }
func invalid(message string) *Error  { return &Error{"invalid_input", message} }
func conflict(message string) *Error { return &Error{"conflict", message} }

// duplicate: the record, or its name or source identity, already exists. A client importing the same statement twice
// treats this as "already there"; it is not a stale edit and not worth retrying.
func duplicate(message string) *Error { return &Error{"duplicate", message} }
func notFound() *Error                { return &Error{"not_found", "Record not found"} }
func PublicError(err error) *Error {
	var known *Error
	if errors.As(err, &known) {
		return known
	}
	if errors.Is(err, pgx.ErrNoRows) {
		return notFound()
	}
	var pg *pgconn.PgError
	if errors.As(err, &pg) {
		switch pg.Code {
		case "23505":
			return duplicate("A record with that name or source identity already exists")
		case "23503":
			return &Error{"conflict", "A referenced record is missing or still in use"}
		case "40001", "40P01":
			// A serialization failure or deadlock: nothing is wrong with the request. Clients retry with the SAME
			// idempotency key (HTTP 503), never treat it as a conflict.
			return &Error{"busy", "The service is busy; retry the request"}
		}
	}
	return &Error{"internal", "The operation could not be completed"}
}

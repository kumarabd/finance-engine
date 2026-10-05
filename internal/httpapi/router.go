package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"strings"

	"github.com/kumarabd/finance-engine/internal/finance"
)

const verifiedUserHeader = "X-Nighthawk-Verified-User"

type Reader interface {
	Accounts(context.Context, string) ([]finance.Account, error)
	Transactions(context.Context, string, int) ([]finance.Transaction, error)
}

func New(reader Reader, ready func(context.Context) error, devUser string) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, r *http.Request) {
		if err := ready(r.Context()); err != nil {
			writeError(w, http.StatusServiceUnavailable, "database_unavailable", "Database is unavailable")
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"status": "ready"})
	})
	mux.HandleFunc("GET /api/v1/accounts", func(w http.ResponseWriter, r *http.Request) {
		owner, ok := ownerID(w, r, devUser)
		if !ok {
			return
		}
		items, err := reader.Accounts(r.Context(), owner)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "internal", "Could not load accounts")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"accounts": items})
	})
	mux.HandleFunc("GET /api/v1/transactions", func(w http.ResponseWriter, r *http.Request) {
		owner, ok := ownerID(w, r, devUser)
		if !ok {
			return
		}
		limit := 50
		if raw := r.URL.Query().Get("limit"); raw != "" {
			value, err := strconv.Atoi(raw)
			if err != nil || value < 1 || value > 100 {
				writeError(w, http.StatusBadRequest, "invalid_limit", "limit must be between 1 and 100")
				return
			}
			limit = value
		}
		items, err := reader.Transactions(r.Context(), owner, limit)
		if err != nil {
			writeError(w, http.StatusInternalServerError, "internal", "Could not load transactions")
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"transactions": items})
	})
	return mux
}

func ownerID(w http.ResponseWriter, r *http.Request, devUser string) (string, bool) {
	owner := strings.TrimSpace(r.Header.Get(verifiedUserHeader))
	if devUser != "" {
		owner = devUser
	}
	if owner == "" || len(owner) > 255 {
		writeError(w, http.StatusUnauthorized, "unauthorized", "Verified user is required")
		return "", false
	}
	return owner, true
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]any{"error": map[string]string{"code": code, "message": message}})
}

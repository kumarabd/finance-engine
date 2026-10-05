package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/kumarabd/finance-engine/internal/finance"
)

type fakeReader struct {
	owner string
	limit int
}

func (f *fakeReader) Accounts(_ context.Context, owner string) ([]finance.Account, error) {
	f.owner = owner
	return []finance.Account{}, nil
}
func (f *fakeReader) Transactions(_ context.Context, owner string, limit int) ([]finance.Transaction, error) {
	f.owner, f.limit = owner, limit
	return []finance.Transaction{}, nil
}

func TestRequiresVerifiedIdentity(t *testing.T) {
	reader := &fakeReader{}
	h := New(reader, func(context.Context) error { return nil }, "")
	request := httptest.NewRequest(http.MethodGet, "/api/v1/accounts", nil)
	response := httptest.NewRecorder()
	h.ServeHTTP(response, request)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d", response.Code)
	}
	if reader.owner != "" {
		t.Fatal("store was called without identity")
	}
}

func TestScopesReadsAndBoundsLimit(t *testing.T) {
	reader := &fakeReader{}
	h := New(reader, func(context.Context) error { return nil }, "")
	request := httptest.NewRequest(http.MethodGet, "/api/v1/transactions?limit=25", nil)
	request.Header.Set(verifiedUserHeader, "user_123")
	response := httptest.NewRecorder()
	h.ServeHTTP(response, request)
	if response.Code != http.StatusOK || reader.owner != "user_123" || reader.limit != 25 {
		t.Fatalf("status=%d owner=%q limit=%d", response.Code, reader.owner, reader.limit)
	}
	var body struct {
		Transactions []finance.Transaction `json:"transactions"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil || body.Transactions == nil {
		t.Fatalf("expected a JSON array, got %s (%v)", response.Body.String(), err)
	}
	request = httptest.NewRequest(http.MethodGet, "/api/v1/transactions?limit=101", nil)
	request.Header.Set(verifiedUserHeader, "user_123")
	response = httptest.NewRecorder()
	h.ServeHTTP(response, request)
	if response.Code != http.StatusBadRequest || reader.limit != 25 {
		t.Fatalf("invalid limit reached store: %d", response.Code)
	}
}

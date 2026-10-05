package transport

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/jsonschema-go/jsonschema"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/kumarabd/finance-engine/internal/engine"
	"github.com/kumarabd/finance-engine/internal/store/postgres"
	"github.com/modelcontextprotocol/go-sdk/mcp"
)

func TestHTTPAuthenticationAndSchema(t *testing.T) {
	s := engine.New(nil)
	handler, err := New(s, http.NotFoundHandler(), "", nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/mcp", "/api/v1/capabilities", "/api/v1/operations/spends_get"} {
		r := httptest.NewRequest("POST", path, strings.NewReader("{}"))
		w := httptest.NewRecorder()
		handler.ServeHTTP(w, r)
		if w.Code != 401 {
			t.Fatalf("%s status=%d", path, w.Code)
		}
	}
	r := httptest.NewRequest("POST", "/api/v1/operations/spends_get", strings.NewReader(`{"id":"x","owner_id":"forged"}`))
	r.Header.Set("X-Nighthawk-Verified-User", "test")
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != 400 {
		t.Fatal(w.Code, w.Body.String())
	}
	r = httptest.NewRequest("POST", "/api/v1/operations/spends_get", strings.NewReader("{}"))
	r.Header.Set("X-Nighthawk-Verified-User", "test")
	r.Header.Set("Origin", "https://untrusted.example")
	r.Header.Set("Sec-Fetch-Site", "cross-site")
	w = httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	if w.Code != 403 {
		t.Fatal("cross-origin write permitted")
	}
}

type authenticated struct{ owner string }

func (a authenticated) RoundTrip(r *http.Request) (*http.Response, error) {
	request := r.Clone(r.Context())
	request.Header = r.Header.Clone()
	request.Header.Set("X-Nighthawk-Verified-User", a.owner)
	request.Header.Set("X-Nighthawk-Verified-Actor", "mcp-test-agent")
	return http.DefaultTransport.RoundTrip(request)
}
func TestHTTPAndMCPShareService(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("TEST_DATABASE_URL is required")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if err = postgres.Migrate(ctx, pool); err != nil {
		t.Fatal(err)
	}
	service := engine.New(pool)
	handler, err := New(service, http.NotFoundHandler(), "", nil)
	if err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(handler)
	defer server.Close()
	owner := "transport-" + rand.Text()
	httpClient := &http.Client{Transport: authenticated{owner}}
	client := mcp.NewClient(&mcp.Implementation{Name: "integration-test", Version: "1"}, nil)
	session, err := client.Connect(ctx, &mcp.StreamableClientTransport{Endpoint: server.URL + "/mcp", HTTPClient: httpClient, DisableStandaloneSSE: true, MaxRetries: -1}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer session.Close()
	tools, err := session.ListTools(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(tools.Tools) != len(service.Operations()) {
		t.Fatal("tool coverage differs")
	}
	catalog := map[string]*engine.Operation{}
	for _, op := range service.Operations() {
		catalog[op.Name] = op
	}
	for _, tool := range tools.Tools {
		op, ok := catalog[tool.Name]
		if !ok {
			t.Fatal(tool.Name)
		}
		if tool.Annotations.ReadOnlyHint == op.Write {
			t.Fatalf("incorrect read/write annotation: %s", tool.Name)
		}
		raw, _ := json.Marshal(tool.InputSchema)
		want, _ := json.Marshal(op.InputSchema)
		var gotShape, wantShape any
		_ = json.Unmarshal(raw, &gotShape)
		_ = json.Unmarshal(want, &wantShape)
		normalized, _ := json.Marshal(gotShape)
		expected, _ := json.Marshal(wantShape)
		if !bytes.Equal(normalized, expected) {
			t.Fatalf("input schema drift: %s", tool.Name)
		}
	}
	input := engine.CreateSpendInput{Meta: engine.Meta{IdempotencyKey: rand.Text()}, Spend: engine.SpendInput{OccurredOn: "2026-10-04", Kind: "expense", AmountMinor: 1599, Currency: "USD"}}
	raw, _ := json.Marshal(input)
	response, err := httpClient.Post(server.URL+"/api/v1/operations/spends_create", "application/json", bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(response.Body)
	response.Body.Close()
	if response.StatusCode != 200 {
		t.Fatal(response.StatusCode, string(body))
	}
	var spend engine.Spend
	if err = json.Unmarshal(body, &spend); err != nil {
		t.Fatal(err)
	}
	result, err := session.CallTool(ctx, &mcp.CallToolParams{Name: "spends_get", Arguments: engine.GetInput{ID: spend.ID}})
	if err != nil || result.IsError {
		t.Fatalf("MCP get: %+v, %v", result, err)
	}
	// Validate actual output against the advertised schema.
	resolved, err := catalog["spends_get"].OutputSchema.Resolve(&jsonschema.ResolveOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if err = resolved.Validate(result.StructuredContent); err != nil {
		t.Fatal("output schema:", err)
	}
	input.Spend.Description = "Updated through MCP"
	update := engine.UpdateSpendInput{Meta: engine.Meta{IdempotencyKey: rand.Text()}, Versioned: engine.Versioned{ID: spend.ID, ExpectedVersion: 1}, Spend: input.Spend}
	result, err = session.CallTool(ctx, &mcp.CallToolParams{Name: "spends_update", Arguments: update})
	if err != nil || result.IsError {
		t.Fatalf("MCP update: %+v, %v", result, err)
	}
	getBody, _ := json.Marshal(engine.GetInput{ID: spend.ID})
	response, err = httpClient.Post(server.URL+"/api/v1/operations/spends_get", "application/json", bytes.NewReader(getBody))
	if err != nil {
		t.Fatal(err)
	}
	body, _ = io.ReadAll(response.Body)
	response.Body.Close()
	if err = json.Unmarshal(body, &spend); err != nil {
		t.Fatal(err)
	}
	if spend.Version != 2 || spend.Description != "Updated through MCP" {
		t.Fatal("interfaces diverged")
	}
	// Identity must come from each HTTP request, never an earlier MCP session.
	otherClient := &http.Client{Transport: authenticated{owner + "-other"}}
	otherSession, err := client.Connect(ctx, &mcp.StreamableClientTransport{Endpoint: server.URL + "/mcp", HTTPClient: otherClient, DisableStandaloneSSE: true, MaxRetries: -1}, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer otherSession.Close()
	result, err = otherSession.CallTool(ctx, &mcp.CallToolParams{Name: "spends_get", Arguments: engine.GetInput{ID: spend.ID}})
	if err != nil || !result.IsError {
		t.Fatalf("cross-owner MCP read: %+v %v", result, err)
	}
	text := result.Content[0].(*mcp.TextContent).Text
	if !strings.Contains(text, "not_found") {
		t.Fatal(text)
	}
	// Domain failures are MCP tool errors that the agent can inspect and correct.
	update.Meta.IdempotencyKey = rand.Text()
	result, err = session.CallTool(ctx, &mcp.CallToolParams{Name: "spends_update", Arguments: update})
	if err != nil || !result.IsError {
		t.Fatal("stale MCP edit accepted")
	}
}

func TestErrorStatusCodes(t *testing.T) {
	for code, want := range map[string]int{"invalid_input": 400, "unauthorized": 401, "not_found": 404, "conflict": 409, "duplicate": 409, "busy": 503, "internal": 500} {
		rec := httptest.NewRecorder()
		writeError(rec, &engine.Error{Code: code, Message: "x"})
		if rec.Code != want {
			t.Errorf("%s: status %d, want %d", code, rec.Code, want)
		}
	}
}

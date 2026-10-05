package postgres

import (
	"context"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

func TestMigrationAndOwnerIsolation(t *testing.T) {
	url := os.Getenv("TEST_DATABASE_URL")
	if url == "" {
		t.Skip("set TEST_DATABASE_URL to an empty PostgreSQL database with TimescaleDB and pgvector enabled")
	}
	ctx := context.Background()
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer pool.Close()
	if err := Migrate(ctx, pool); err != nil {
		t.Fatal(err)
	}
	if err := Migrate(ctx, pool); err != nil {
		t.Fatalf("second migration: %v", err)
	}

	owner := "integration-owner-" + time.Now().UTC().Format("20060102150405.000000000")
	var accountID string
	err = pool.QueryRow(ctx, `INSERT INTO finance.accounts (owner_id, name, kind, currency)
		VALUES ($1, 'Test account', 'checking', 'USD') RETURNING id`, owner).Scan(&accountID)
	if err != nil {
		t.Fatal(err)
	}
	_, err = pool.Exec(ctx, `INSERT INTO finance.transactions
		(owner_id, account_id, posted_at, amount_minor, currency, description)
		VALUES ($1, $2, $3, 12345, 'USD', 'Test deposit')`, owner, accountID, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}

	store := Store{Pool: pool}
	accounts, err := store.Accounts(ctx, owner)
	if err != nil || len(accounts) != 1 || accounts[0].ID != accountID {
		t.Fatalf("owner accounts = %#v, %v", accounts, err)
	}
	transactions, err := store.Transactions(ctx, owner, 50)
	if err != nil || len(transactions) != 1 || transactions[0].AmountMinor != 12345 {
		t.Fatalf("owner transactions = %#v, %v", transactions, err)
	}
	otherAccounts, err := store.Accounts(ctx, owner+"-other")
	if err != nil || len(otherAccounts) != 0 {
		t.Fatalf("other accounts = %#v, %v", otherAccounts, err)
	}
	otherTransactions, err := store.Transactions(ctx, owner+"-other", 50)
	if err != nil || len(otherTransactions) != 0 {
		t.Fatalf("other transactions = %#v, %v", otherTransactions, err)
	}
}

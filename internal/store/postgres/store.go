package postgres

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/kumarabd/finance-engine/internal/finance"
)

type Store struct{ Pool *pgxpool.Pool }

func (s Store) Accounts(ctx context.Context, owner string) ([]finance.Account, error) {
	rows, err := s.Pool.Query(ctx, `SELECT id, name, kind, currency, created_at
		FROM finance.accounts WHERE owner_id = $1 ORDER BY created_at DESC, id DESC`, owner)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := make([]finance.Account, 0)
	for rows.Next() {
		var item finance.Account
		if err := rows.Scan(&item.ID, &item.Name, &item.Kind, &item.Currency, &item.CreatedAt); err != nil {
			return nil, err
		}
		item.CreatedAt = item.CreatedAt.UTC()
		items = append(items, item)
	}
	return items, rows.Err()
}

func (s Store) Transactions(ctx context.Context, owner string, limit int) ([]finance.Transaction, error) {
	if limit < 1 || limit > 100 {
		return nil, fmt.Errorf("limit must be between 1 and 100")
	}
	rows, err := s.Pool.Query(ctx, `SELECT id, account_id, posted_at, amount_minor, currency, description
		FROM finance.transactions WHERE owner_id = $1 ORDER BY posted_at DESC, id DESC LIMIT $2`, owner, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := make([]finance.Transaction, 0)
	for rows.Next() {
		var item finance.Transaction
		if err := rows.Scan(&item.ID, &item.AccountID, &item.PostedAt, &item.AmountMinor, &item.Currency, &item.Description); err != nil {
			return nil, err
		}
		item.PostedAt = item.PostedAt.UTC()
		items = append(items, item)
	}
	return items, rows.Err()
}

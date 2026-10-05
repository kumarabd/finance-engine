package finance

import "time"

// Money is represented in the currency's smallest unit to avoid float rounding.
type Account struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	Kind      string    `json:"kind"`
	Currency  string    `json:"currency"`
	CreatedAt time.Time `json:"created_at"`
}

type Transaction struct {
	ID          string    `json:"id"`
	AccountID   string    `json:"account_id"`
	PostedAt    time.Time `json:"posted_at"`
	AmountMinor int64     `json:"amount_minor"`
	Currency    string    `json:"currency"`
	Description string    `json:"description"`
}

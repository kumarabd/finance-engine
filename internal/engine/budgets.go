package engine

import (
	"context"
	"strconv"
	"time"
)

// Budgets are limits you set on spending, and the notification when you cross
// one. Deliberately one concept, not two: "tell me when dining exceeds 20,000
// this month" and "my dining budget is 20,000 this month" are the same sentence,
// so the limit and the alert live on the same row and no separate watch table
// points at a budget.
//
// The design point: the limit is evaluated where the data lands. A spend is
// written, and the same transaction asks whether it pushed any budget past its
// alert line. There is no scheduler, no poll loop and no worker, so a budget
// costs one aggregate query per active budget on the writes that could affect it,
// rather than a poll that costs the same whether anything happened or not.
//
// Crossings are *recorded* here, not delivered. Delivery belongs to the waking
// side; `callback_token` is opaque to this engine — it neither parses it nor
// trusts it, it only echoes it back.

// periodBounds bounds a calendar date to the period containing it, in calendar
// terms: "this month" is the question people actually ask. Dates stay strings —
// this engine never converts `occurred_on` to a timezone-bearing time.
func periodBounds(occurredOn, period string) (from, to, key string, err error) {
	day, err := time.Parse("2006-01-02", occurredOn)
	if err != nil {
		return "", "", "", invalid("occurred_on must be YYYY-MM-DD")
	}
	switch period {
	case "month":
		first := time.Date(day.Year(), day.Month(), 1, 0, 0, 0, 0, time.UTC)
		return first.Format("2006-01-02"), first.AddDate(0, 1, -1).Format("2006-01-02"), first.Format("2006-01"), nil
	case "week":
		// ISO weeks start Monday. Go's Weekday() puts Sunday at 0, so shift it.
		offset := (int(day.Weekday()) + 6) % 7
		first := day.AddDate(0, 0, -offset)
		return first.Format("2006-01-02"), first.AddDate(0, 0, 6).Format("2006-01-02"), first.Format("2006-01-02"), nil
	default:
		return "", "", "", invalid("period must be week or month")
	}
}

// today reads the current date from the database rather than the process clock,
// so "this month" means the same thing to every replica and to every query.
func (u *unit) today(ctx context.Context) (string, error) {
	var today string
	err := u.tx.QueryRow(ctx, "SELECT CURRENT_DATE::text").Scan(&today)
	return today, err
}

// alertMinor is the amount the total must exceed for this budget to fire. An
// alert at 80% of a 20,000 limit is 16,000 — the point being that you hear about
// it while there is still room to act.
func alertMinor(limit int64, notifyAtPercent int) int64 {
	alert := limit * int64(notifyAtPercent) / 100
	if alert < 1 {
		alert = 1
	}
	return alert
}

const budgetColumns = `b.id::text,b.version,b.name,b.kind,b.category_id::text,COALESCE(d.name,''),
	b.period,b.limit_minor,b.currency,b.notify_at_percent,b.callback_token,b.active,b.created_at,b.updated_at`

func scanBudget(row scanner) (Budget, error) {
	var b Budget
	var categoryID *string
	err := row.Scan(&b.ID, &b.Version, &b.Name, &b.Kind, &categoryID, &b.CategoryName,
		&b.Period, &b.LimitMinor, &b.Currency, &b.NotifyAtPercent, &b.CallbackToken,
		&b.Active, &b.CreatedAt, &b.UpdatedAt)
	if err != nil {
		return b, err
	}
	if categoryID != nil {
		b.CategoryID = *categoryID
	}
	b.CreatedAt = b.CreatedAt.UTC()
	b.UpdatedAt = b.UpdatedAt.UTC()
	return b, nil
}

// getBudget returns the raw scan error when nothing matches; `PublicError` maps
// pgx.ErrNoRows to not_found centrally, the same as every other read here.
func (u *unit) getBudget(ctx context.Context, id string) (Budget, error) {
	if err := uuid(id); err != nil {
		return Budget{}, err
	}
	return scanBudget(u.tx.QueryRow(ctx,
		`SELECT `+budgetColumns+` FROM finance.budgets b
		 LEFT JOIN finance.dimensions d ON d.owner_id=b.owner_id AND d.id=b.category_id
		 WHERE b.owner_id=$1 AND b.id=$2 AND b.deleted_at IS NULL`, u.owner, id))
}

func (u *unit) createBudget(ctx context.Context, input BudgetInput) (Budget, error) {
	if _, _, _, err := periodBounds("2026-01-01", input.Period); err != nil {
		return Budget{}, err
	}
	if input.Currency == "" {
		return Budget{}, invalid("currency is required; a limit is meaningless without one")
	}
	if input.LimitMinor < 1 {
		return Budget{}, invalid("limit_minor must be positive")
	}
	if input.NotifyAtPercent == 0 {
		input.NotifyAtPercent = 100
	}
	if input.NotifyAtPercent < 1 || input.NotifyAtPercent > 200 {
		return Budget{}, invalid("notify_at_percent must be 1-200")
	}
	// An omitted category means a budget on all spending. A supplied one must be
	// this owner's and active: the composite FK makes a foreign one
	// unrepresentable, and this check exists for the message.
	kind := "category"
	if input.CategoryID == "" {
		kind = "total"
	} else {
		var exists bool
		err := u.tx.QueryRow(ctx,
			`SELECT EXISTS(SELECT 1 FROM finance.dimensions
			 WHERE owner_id=$1 AND id=$2 AND kind='category' AND deleted_at IS NULL)`,
			u.owner, input.CategoryID).Scan(&exists)
		if err != nil {
			return Budget{}, err
		}
		if !exists {
			return Budget{}, invalid("category_id must be an active category of yours, or omitted to budget all spending")
		}
	}

	var id string
	err := u.tx.QueryRow(ctx,
		`INSERT INTO finance.budgets(owner_id,name,kind,category_id,period,limit_minor,currency,notify_at_percent,callback_token)
		 VALUES($1,$2,$3,NULLIF($4,'')::uuid,$5,$6,$7,$8,$9) RETURNING id::text`,
		u.owner, input.Name, kind, input.CategoryID, input.Period, input.LimitMinor,
		input.Currency, input.NotifyAtPercent, input.CallbackToken).Scan(&id)
	if err != nil {
		return Budget{}, err
	}
	result, err := u.getBudget(ctx, id)
	if err != nil {
		return result, err
	}
	return result, u.audit(ctx, "budget", id, nil, result)
}

func (u *unit) updateBudget(ctx context.Context, id string, expected int64, input BudgetInput) (Budget, error) {
	before, err := u.getBudget(ctx, id)
	if err != nil {
		return Budget{}, err
	}
	if err := version(expected, before.Version); err != nil {
		return Budget{}, err
	}
	if _, _, _, err := periodBounds("2026-01-01", input.Period); err != nil {
		return Budget{}, err
	}
	if input.LimitMinor < 1 || input.NotifyAtPercent < 1 || input.NotifyAtPercent > 200 {
		return Budget{}, invalid("limit_minor must be positive and notify_at_percent 1-200")
	}
	// Category and kind are fixed at creation. Moving a budget to another category
	// would silently invalidate every fire already recorded against the old one;
	// delete and recreate instead, which says so out loud.
	_, err = u.tx.Exec(ctx,
		`UPDATE finance.budgets SET name=$3,period=$4,limit_minor=$5,currency=$6,notify_at_percent=$7,
		        version=version+1, updated_at=clock_timestamp()
		 WHERE owner_id=$1 AND id=$2`,
		u.owner, id, input.Name, input.Period, input.LimitMinor, input.Currency, input.NotifyAtPercent)
	if err != nil {
		return Budget{}, err
	}
	after, err := u.getBudget(ctx, id)
	if err != nil {
		return after, err
	}
	return after, u.audit(ctx, "budget", id, before, after)
}

func (u *unit) listBudgets(ctx context.Context, input ListBudgetsInput) (BudgetsResult, error) {
	limit, err := page(input.Limit, input.Offset)
	result := BudgetsResult{Items: []Budget{}}
	if err != nil {
		return result, err
	}
	rows, err := u.tx.Query(ctx,
		`SELECT `+budgetColumns+` FROM finance.budgets b
		 LEFT JOIN finance.dimensions d ON d.owner_id=b.owner_id AND d.id=b.category_id
		 WHERE b.owner_id=$1 AND b.deleted_at IS NULL AND ($2::bool OR b.active)
		 ORDER BY lower(b.name) LIMIT $3 OFFSET $4`, u.owner, input.IncludeInactive, limit, input.Offset)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		b, err := scanBudget(rows)
		if err != nil {
			return result, err
		}
		result.Items = append(result.Items, b)
	}
	return result, rows.Err()
}

func (u *unit) deleteBudget(ctx context.Context, input LifecycleInput) (Budget, error) {
	existing, err := u.getBudget(ctx, input.ID)
	if err != nil {
		return Budget{}, err
	}
	if err := version(input.ExpectedVersion, existing.Version); err != nil {
		return Budget{}, err
	}
	_, err = u.tx.Exec(ctx,
		`UPDATE finance.budgets SET deleted_at=now(), version=version+1, updated_at=clock_timestamp()
		 WHERE owner_id=$1 AND id=$2`, u.owner, input.ID)
	if err != nil {
		return Budget{}, err
	}
	return u.getBudget(ctx, input.ID)
}

// spentFor sums one budget's spending over a period.
//
// It reuses the same `buckets` aggregate `spending_analyze` uses, so a budget and
// a reported total can never disagree about the same month — including on the two
// rules that matter most here: transfers are excluded, and currencies are never
// combined or converted. Whatever that aggregate decides a category's total is,
// the budget agrees, because it is the same call.
func (u *unit) spentFor(ctx context.Context, b Budget, from, to string) (int64, error) {
	buckets, err := u.buckets(ctx, Filter{From: from, To: to, Currency: b.Currency, CategoryID: b.CategoryID}, "total")
	if err != nil {
		return 0, err
	}
	var total int64
	for _, bucket := range buckets {
		minor, err := strconv.ParseInt(bucket.ExpenseMinor, 10, 64)
		if err != nil {
			return 0, err
		}
		total += minor
	}
	return total, nil
}

// budgetStatus is what a budgets screen renders: for every active budget, how
// much of this period's limit has been used, what is left, and whether the alert
// line has been crossed.
//
// Computed from live spending rather than from the fire log, so a status read
// never depends on whether a notification happened to be delivered — a budget is
// true about the ledger whether or not anybody heard about it.
func (u *unit) budgetStatus(ctx context.Context, input StatusInput) (StatusResult, error) {
	result := StatusResult{Items: []BudgetStatus{}}
	today, err := u.today(ctx)
	if err != nil {
		return result, err
	}
	rows, err := u.tx.Query(ctx,
		`SELECT `+budgetColumns+` FROM finance.budgets b
		 LEFT JOIN finance.dimensions d ON d.owner_id=b.owner_id AND d.id=b.category_id
		 WHERE b.owner_id=$1 AND b.deleted_at IS NULL AND ($2::bool OR b.active)
		 ORDER BY lower(b.name)`, u.owner, input.IncludeInactive)
	if err != nil {
		return result, err
	}
	var budgets []Budget
	for rows.Next() {
		b, err := scanBudget(rows)
		if err != nil {
			return result, err
		}
		budgets = append(budgets, b)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return result, err
	}

	for _, b := range budgets {
		from, to, key, err := periodBounds(today, b.Period)
		if err != nil {
			return result, err
		}
		spent, err := u.spentFor(ctx, b, from, to)
		if err != nil {
			return result, err
		}
		alert := alertMinor(b.LimitMinor, b.NotifyAtPercent)
		percent := int(spent * 100 / b.LimitMinor)
		result.Items = append(result.Items, BudgetStatus{
			Budget:         b,
			PeriodKey:      key,
			PeriodFrom:     from,
			PeriodTo:       to,
			SpentMinor:     spent,
			RemainingMinor: max64(b.LimitMinor-spent, 0),
			Percent:        percent,
			AlertMinor:     alert,
			Alerted:        spent > alert,
		})
	}
	return result, nil
}

func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

func (u *unit) listBudgetFires(ctx context.Context, input ListFiresInput) (FiresResult, error) {
	limit, err := page(input.Limit, input.Offset)
	result := FiresResult{Items: []BudgetFire{}}
	if err != nil {
		return result, err
	}
	rows, err := u.tx.Query(ctx,
		`SELECT f.id::text,f.budget_id::text,b.name,f.period_key,f.observed_minor,f.limit_minor,
		        f.alert_minor,f.percent,f.currency,f.fired_at,f.delivered_at
		 FROM finance.budget_fires f
		 JOIN finance.budgets b ON b.owner_id=f.owner_id AND b.id=f.budget_id
		 WHERE f.owner_id=$1 AND ($2='' OR f.budget_id::text=$2)
		 ORDER BY f.fired_at DESC LIMIT $3 OFFSET $4`, u.owner, input.BudgetID, limit, input.Offset)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		var f BudgetFire
		if err := rows.Scan(&f.ID, &f.BudgetID, &f.Name, &f.PeriodKey, &f.ObservedMinor, &f.LimitMinor,
			&f.AlertMinor, &f.Percent, &f.Currency, &f.FiredAt, &f.DeliveredAt); err != nil {
			return result, err
		}
		f.FiredAt = f.FiredAt.UTC()
		result.Items = append(result.Items, f)
	}
	return result, rows.Err()
}

// evaluateBudgets asks whether this spend pushed any budget past its alert line,
// and records each crossing once.
//
// Every active budget in the spend's currency is recomputed rather than only the
// ones whose category this spend names. That is deliberate: a spend can be split
// across several categories, so "which budgets does this touch?" is not a
// question the spend alone answers. Recomputing each budget's own total is
// correct regardless of how the spend was allocated, and the cost is bounded by
// how many budgets a person has, which is a handful.
func (u *unit) evaluateBudgets(ctx context.Context, spend Spend) error {
	// A refund moves a total the other way and can never cross a limit upward.
	// Skipping is the correctness rule, not an optimisation.
	if spend.Kind != "expense" {
		return nil
	}
	rows, err := u.tx.Query(ctx,
		`SELECT `+budgetColumns+` FROM finance.budgets b
		 LEFT JOIN finance.dimensions d ON d.owner_id=b.owner_id AND d.id=b.category_id
		 WHERE b.owner_id=$1 AND b.deleted_at IS NULL AND b.active AND b.currency=$2`,
		u.owner, spend.Currency)
	if err != nil {
		return err
	}
	var budgets []Budget
	for rows.Next() {
		b, err := scanBudget(rows)
		if err != nil {
			return err
		}
		budgets = append(budgets, b)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}

	for _, b := range budgets {
		from, to, key, err := periodBounds(spend.OccurredOn, b.Period)
		if err != nil {
			return err
		}
		spent, err := u.spentFor(ctx, b, from, to)
		if err != nil {
			return err
		}
		alert := alertMinor(b.LimitMinor, b.NotifyAtPercent)
		// Strictly greater: a limit reached exactly is not yet a crossing, so
		// spending to the line does not fire.
		if spent <= alert {
			continue
		}
		// ON CONFLICT DO NOTHING against the per-period unique index is what makes
		// this fire once per crossing: every later spend in the same period
		// recomputes the same over-alert total and hits the same conflict. A budget
		// that fired at 80% and then went to 120% is one crossing of one line, not
		// two events.
		_, err = u.tx.Exec(ctx,
			`INSERT INTO finance.budget_fires(owner_id,budget_id,period_key,observed_minor,limit_minor,alert_minor,percent,currency)
			 VALUES($1,$2,$3,$4,$5,$6,$7,$8) ON CONFLICT DO NOTHING`,
			u.owner, b.ID, key, spent, b.LimitMinor, alert, int(spent*100/b.LimitMinor), spend.Currency)
		if err != nil {
			return err
		}
	}
	return nil
}

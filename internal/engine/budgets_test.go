package engine

import "testing"

// Period bounds are the fiddly part of a budget: they decide which calendar month
// or week a crossing belongs to, and the week case has a real trap — Go's
// Weekday() puts Sunday at 0 while ISO weeks start Monday, so a Sunday date must
// still resolve to the week that began six days earlier.
func TestPeriodBounds(t *testing.T) {
	cases := []struct {
		occurredOn, period, from, to, key string
	}{
		{"2026-10-08", "month", "2026-10-01", "2026-10-31", "2026-10"},
		{"2026-02-15", "month", "2026-02-01", "2026-02-28", "2026-02"},
		{"2026-12-31", "month", "2026-12-01", "2026-12-31", "2026-12"},
		// 2026-10-08 is a Thursday; its ISO week runs Mon 10-05 to Sun 10-11.
		{"2026-10-08", "week", "2026-10-05", "2026-10-11", "2026-10-05"},
		// A Monday starts its own week.
		{"2026-10-05", "week", "2026-10-05", "2026-10-11", "2026-10-05"},
		// A Sunday belongs to the week that began six days earlier, not the next one.
		{"2026-10-11", "week", "2026-10-05", "2026-10-11", "2026-10-05"},
		// Crossing a month boundary inside one week.
		{"2026-11-01", "week", "2026-10-26", "2026-11-01", "2026-10-26"},
	}
	for _, c := range cases {
		from, to, key, err := periodBounds(c.occurredOn, c.period)
		if err != nil {
			t.Fatalf("%s %s: %v", c.occurredOn, c.period, err)
		}
		if from != c.from || to != c.to || key != c.key {
			t.Errorf("%s %s = (%s, %s, %s), want (%s, %s, %s)",
				c.occurredOn, c.period, from, to, key, c.from, c.to, c.key)
		}
	}
	for _, bad := range []struct{ on, period string }{
		{"2026-10-08", "fortnight"},
		{"2026-10-08", ""},
		{"08/10/2026", "month"},
	} {
		if _, _, _, err := periodBounds(bad.on, bad.period); err == nil {
			t.Errorf("periodBounds(%q, %q) should have failed", bad.on, bad.period)
		}
	}
}

// The alert line is what actually decides whether anyone hears anything, so its
// arithmetic is worth pinning: an alert at 80% of a limit must land on the limit's
// own minor units, and must never round down to zero for a tiny limit — a zero
// alert line would fire on the first unit of spending.
func TestAlertMinor(t *testing.T) {
	cases := []struct {
		limit    int64
		percent  int
		expected int64
	}{
		{2000000, 100, 2000000}, // fire only on going over
		{2000000, 80, 1600000},  // warn with room left to act
		{2000000, 200, 4000000},
		{1, 1, 1}, // never rounds to zero
		{3, 1, 1}, // 1% of 3 truncates to 0, which must not become a zero threshold
		{100, 150, 150},
	}
	for _, c := range cases {
		if got := alertMinor(c.limit, c.percent); got != c.expected {
			t.Errorf("alertMinor(%d, %d) = %d, want %d", c.limit, c.percent, got, c.expected)
		}
	}
}

// Budget operations are the tools an agent picks by, so their names are part of
// the contract.
func TestBudgetOperationsExist(t *testing.T) {
	s := New(nil)
	want := []string{
		"budgets_create",
		"budgets_list",
		"budgets_get",
		"budgets_update",
		"budgets_delete",
		"budget_status",
		"budget_fires_list",
	}
	have := map[string]bool{}
	for _, op := range s.Operations() {
		have[op.Name] = true
	}
	for _, name := range want {
		if !have[name] {
			t.Errorf("missing operation %q", name)
		}
	}
	// The generic name is what this replaced. It must not come back: a limit and
	// the alert that goes with it are one thing, not a subscription pointing at a
	// budget.
	if have["subscriptions_create"] {
		t.Error("subscriptions_create is back — a budget is one concept, not a watcher plus a limit")
	}
}

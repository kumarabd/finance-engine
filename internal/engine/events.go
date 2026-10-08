package engine

import (
	"context"
	"fmt"
)

// The delivery contract — the two operations every engine implements so mcp-hub can
// collect what it noticed without understanding any of it.
//
// These are deliberately the only operations here named for a mechanism rather than
// a use case. Everything else in this engine is named so the agent can pick it by
// outcome; these two are not for the agent at all. mcp-hub finds them by name on
// every connection and calls them blind, and that fixed pair is what lets one
// delivery loop serve budget crossings and map arrivals alike. See
// agent-harness/docs/components/event-delivery.md.
//
// Delivery stops here. Nothing in this engine reaches outwards: it records what it
// noticed and answers when asked. Getting it delivered, retrying, and waking whoever
// subscribed is mcp-hub's job, which is why there is no callback URL, no token and no
// outbound queue in this codebase.

// eventsPending answers what this engine has noticed and not yet handed over. The
// read is deliberately unordered by anything but age: [Event] carries no priority,
// because ordering notices against each other is not something the engine knows how
// to do and not something the delivery loop needs.
func (u *unit) eventsPending(ctx context.Context) (EventsResult, error) {
	result := EventsResult{Items: []Event{}}
	rows, err := u.tx.Query(ctx,
		`SELECT f.id::text,f.budget_id::text,b.name,b.period,f.percent
		 FROM finance.budget_fires f
		 JOIN finance.budgets b ON b.owner_id=f.owner_id AND b.id=f.budget_id
		 WHERE f.owner_id=$1 AND f.delivered_at IS NULL
		 ORDER BY f.fired_at LIMIT 100`, u.owner)
	if err != nil {
		return result, err
	}
	defer rows.Close()
	for rows.Next() {
		var id, budgetID, name, period string
		var percent int
		if err := rows.Scan(&id, &budgetID, &name, &period, &percent); err != nil {
			return result, err
		}
		span := period
		if period == "week" {
			span = "weekly"
		} else if period == "month" {
			span = "monthly"
		}
		result.Items = append(result.Items, Event{
			// Deliberately NOT scoped by period. A subscription is a standing
			// intent — "tell me when Dining out goes over" — so a key carrying the
			// period would silently stop matching the moment the calendar moved on,
			// which is the worst kind of bug: invisible until it bites a month
			// later. Once per period is already handled by the fire itself, whose
			// id is unique per crossing, so EventID is what distinguishes them.
			EventKey:  "budget:" + budgetID,
			EventID:   id,
			Objective: fmt.Sprintf("%s is at %d%% of its %s budget.", name, percent, span),
		})
	}
	return result, rows.Err()
}

// eventsAck marks notices handed over, so the next poll does not return them again.
//
// Idempotent by construction (`delivered_at IS NULL`), because the caller may ack a
// batch it already acked and must not be able to fail on that. An ack is bookkeeping
// on a derived record, so it writes no audit entry — the same reason the crossing
// that created the row does not.
func (u *unit) eventsAck(ctx context.Context, input AckEventsInput) (AckEventsResult, error) {
	result := AckEventsResult{}
	if len(input.EventIDs) == 0 {
		return result, nil
	}
	tag, err := u.tx.Exec(ctx,
		`UPDATE finance.budget_fires SET delivered_at=now()
		 WHERE owner_id=$1 AND delivered_at IS NULL AND id::text = ANY($2)`,
		u.owner, input.EventIDs)
	if err != nil {
		return result, err
	}
	result.Acknowledged = int(tag.RowsAffected())
	return result, nil
}

package engine

import "context"

func (s *Service) registerOperations() {
	register(s, "spends_create", "Record an expense, refund, or excluded transfer with optional splits, tags, merchant and evidence.", true,
		func(ctx context.Context, u *unit, in CreateSpendInput) (Spend, error) {
			return u.createSpend(ctx, in.Spend)
		})
	register(s, "spends_get", "Read a spend by ID, including deleted records and its current version.", false,
		func(ctx context.Context, u *unit, in GetInput) (Spend, error) { return u.getSpend(ctx, in.ID) })
	register(s, "spends_update", "Replace all editable spend fields using the version last read. Omitted optional fields are cleared.", true,
		func(ctx context.Context, u *unit, in UpdateSpendInput) (Spend, error) {
			return u.updateSpend(ctx, in.ID, in.ExpectedVersion, in.Spend)
		})
	register(s, "spends_delete", "Soft-delete a spend, retaining evidence and history. Active linked refunds must be removed or unlinked first.", true,
		func(ctx context.Context, u *unit, in LifecycleInput) (Spend, error) {
			return u.lifecycleSpend(ctx, in.ID, in.ExpectedVersion, false)
		})
	register(s, "spends_restore", "Restore a deleted spend. Its referenced records must be active.", true,
		func(ctx context.Context, u *unit, in LifecycleInput) (Spend, error) {
			return u.lifecycleSpend(ctx, in.ID, in.ExpectedVersion, true)
		})
	register(s, "spends_bulk_create", "Create 1-100 spends atomically. Any invalid record rolls back the entire batch.", true,
		func(ctx context.Context, u *unit, in BulkCreateInput) (SpendsResult, error) {
			result := SpendsResult{Items: []Spend{}}
			if len(in.Spends) < 1 || len(in.Spends) > 100 {
				return result, invalid("A batch must contain 1-100 spends")
			}
			for _, input := range in.Spends {
				spend, err := u.createSpend(ctx, input)
				if err != nil {
					return result, err
				}
				result.Items = append(result.Items, spend)
			}
			return result, nil
		})
	register(s, "spends_bulk_update", "Apply a shared patch to 1-100 versioned spends atomically. Supports bulk categorization and adding, removing or replacing tags.", true, bulkUpdate)
	register(s, "spends_bulk_delete", "Soft-delete 1-100 versioned spends atomically, removing refunds before their original expenses.", true,
		func(ctx context.Context, u *unit, in BulkLifecycleInput) (SpendsResult, error) {
			return bulkLifecycle(ctx, u, in, false)
		})
	register(s, "spends_bulk_restore", "Restore 1-100 versioned spends atomically, restoring original expenses before linked refunds.", true,
		func(ctx context.Context, u *unit, in BulkLifecycleInput) (SpendsResult, error) {
			return bulkLifecycle(ctx, u, in, true)
		})
	register(s, "spends_search", "Filter and paginate spends. Category filters match any allocation; returned records retain all their splits. All tag filters must match.", false, searchSpends)
	register(s, "spends_export", "Export one filtered page as CSV, with total and next_offset. Each page includes a header. Repeat with next_offset for all records.", false, exportSpends)
	register(s, "spending_analyze", "Exact spending totals, trends and comparisons. Excludes transfers, subtracts refunds on their own dates, and separates currencies. Category filters sum matching splits only. Tag groups overlap.", false, analyze)
	for _, kind := range []string{"category", "tag", "merchant"} {
		prefix := map[string]string{"category": "categories", "tag": "tags", "merchant": "merchants"}[kind]
		register(s, prefix+"_create", "Create a "+kind+". Names are unique per user, ignoring case.", true,
			func(ctx context.Context, u *unit, in CreateDimensionInput) (Dimension, error) {
				return createDimension(ctx, u, in, kind)
			})
		register(s, prefix+"_get", "Read a "+kind+" by ID, including deleted records.", false,
			func(ctx context.Context, u *unit, in GetInput) (Dimension, error) {
				return u.getDimension(ctx, in.ID, kind)
			})
		register(s, prefix+"_list", "Search and paginate "+prefix+", with optional deleted records.", false,
			func(ctx context.Context, u *unit, in PageInput) (Page[Dimension], error) {
				return listDimensions(ctx, u, in, kind)
			})
		register(s, prefix+"_update", "Rename or edit a "+kind+" using its current version. Existing spend links are preserved.", true,
			func(ctx context.Context, u *unit, in UpdateDimensionInput) (Dimension, error) {
				return updateDimension(ctx, u, in, kind)
			})
		register(s, prefix+"_delete", "Delete a "+kind+". Referenced records require replacement_id and replacement_version; reassignment is atomic and audited.", true,
			func(ctx context.Context, u *unit, in DeleteDimensionInput) (Dimension, error) {
				return deleteDimension(ctx, u, in, kind)
			})
		register(s, prefix+"_restore", "Restore an unused "+kind+" if its name and aliases are available. Merged references are not moved back.", true,
			func(ctx context.Context, u *unit, in LifecycleInput) (Dimension, error) {
				return restoreDimension(ctx, u, in, kind)
			})
		register(s, prefix+"_merge", "Merge a "+kind+" into an active target, reassign every spend, combine matching splits or tags, and retain history. Merchants retain the source name as an alias.", true,
			func(ctx context.Context, u *unit, in MergeDimensionInput) (MergeResult, error) {
				return mergeDimension(ctx, u, in, kind)
			})
	}
	register(s, "evidence_create", "Store an optional supporting document reference. Does not fetch or parse documents.", true, createEvidence)
	register(s, "evidence_get", "Read evidence metadata by ID, including deleted records.", false,
		func(ctx context.Context, u *unit, in GetInput) (Evidence, error) { return u.getEvidence(ctx, in.ID) })
	register(s, "evidence_list", "Search and paginate evidence metadata.", false, listEvidence)
	register(s, "evidence_update", "Replace evidence metadata using the version last read.", true, updateEvidence)
	register(s, "evidence_delete", "Delete evidence metadata. Linked records require detach=true; every detached spend receives a new version and audit entry.", true, deleteEvidence)
	register(s, "evidence_restore", "Restore deleted evidence metadata. Previously detached spend links are not recreated.", true, restoreEvidence)
	register(s, "evidence_attach", "Attach evidence to a spend. id and expected_version refer to the spend.", true,
		func(ctx context.Context, u *unit, in EvidenceLinkInput) (Spend, error) {
			return linkEvidence(ctx, u, in, true)
		})
	register(s, "evidence_detach", "Detach evidence from a spend. id and expected_version refer to the spend.", true,
		func(ctx context.Context, u *unit, in EvidenceLinkInput) (Spend, error) {
			return linkEvidence(ctx, u, in, false)
		})
	register(s, "history_list", "Read the complete paginated before/after change history for a record, including actor and operation.", false, history)

	// Budgets — a limit you set, and the crossing you get told about.
	//
	// Named for the use case, not for the mechanism. These operations ARE the
	// tools an agent picks by, so "keep me to 20k on dining this month" has to map
	// onto `budgets_create` with a category and a limit directly — against a
	// generic `subscriptions_create(kind=..., threshold_minor=...)` the model must
	// first infer which kind, which period, and that the amount is in minor units,
	// and three inferences are three ways to get it wrong. Nothing else in this
	// engine's operations is generic either; there is no objects_create(kind=...)
	// anywhere.
	//
	// The limit is evaluated when a spend is written, so a crossing is caught as
	// it happens. Recording one is this engine's job; delivering it belongs to the
	// waking side.
	register(s, "budgets_create", "Set a spending limit for a calendar period, on one category or on all spending, with the percentage of it that should raise an alert. Evaluated when a spend is written, so a crossing is caught as it happens.", true,
		func(ctx context.Context, u *unit, in CreateBudgetInput) (Budget, error) {
			return u.createBudget(ctx, in.Budget)
		})
	register(s, "budgets_list", "List your budgets, active ones by default.", false,
		func(ctx context.Context, u *unit, in ListBudgetsInput) (BudgetsResult, error) {
			return u.listBudgets(ctx, in)
		})
	register(s, "budgets_get", "Read one budget by ID.", false,
		func(ctx context.Context, u *unit, in GetInput) (Budget, error) {
			return u.getBudget(ctx, in.ID)
		})
	register(s, "budgets_update", "Change a budget's name, period, limit, currency or alert percentage. Its category is fixed at creation.", true,
		func(ctx context.Context, u *unit, in UpdateBudgetInput) (Budget, error) {
			return u.updateBudget(ctx, in.ID, in.ExpectedVersion, in.Budget)
		})
	register(s, "budgets_delete", "Remove a budget. It is soft-deleted and the crossings it already recorded are kept.", true,
		func(ctx context.Context, u *unit, in LifecycleInput) (Budget, error) {
			return u.deleteBudget(ctx, in)
		})
	register(s, "budget_status", "How every budget is doing in the current period: spent, remaining, percent used, and whether the alert line has been crossed. Computed from live spending.", false,
		func(ctx context.Context, u *unit, in StatusInput) (StatusResult, error) {
			return u.budgetStatus(ctx, in)
		})
	register(s, "budget_fires_list", "Read the alert lines your budgets have crossed, newest first — what they noticed and when.", false,
		func(ctx context.Context, u *unit, in ListFiresInput) (FiresResult, error) {
			return u.listBudgetFires(ctx, in)
		})

	// The delivery contract (events.go) — the only two operations here named for a
	// mechanism rather than a use case, because they are not for the agent. mcp-hub
	// looks for them by name on every connection and calls them blind, which is what
	// lets one delivery loop serve this engine and the maps engine alike.
	register(s, "events_pending", "Hand over the alerts this engine has recorded and not yet delivered. Called by the hub's delivery loop, not by the agent.", false,
		func(ctx context.Context, u *unit, _ struct{}) (EventsResult, error) {
			return u.eventsPending(ctx)
		})
	register(s, "events_ack", "Mark alerts delivered so they are not handed over twice. Called by the hub's delivery loop, not by the agent.", true,
		func(ctx context.Context, u *unit, in AckEventsInput) (AckEventsResult, error) {
			return u.eventsAck(ctx, in)
		})
}

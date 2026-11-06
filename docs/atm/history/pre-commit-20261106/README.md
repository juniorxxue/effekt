# Inference reports before the commit preparation

These are the unchanged inference reports and table used by the earlier
2026-11-06 supplement build. A subsequent TODO comment in
`polymorphic-outer-call.effekt` changed its source hash without changing the
program. The commit preparation preserves that comment, removes trailing
whitespace, and refreshes the inspection and timing reports against the
committed source. These archived measurements remain the provenance for the
previously built PDF; the current reports are under `docs/atm/results`.

# 0007 - Choreography over orchestration

## Context
The flow is short and linear: submit, analyse, report. Something must decide what happens next.

## Decision
Choreography: every service reacts to events and publishes its own events. There is no central
orchestrator service. Ingestion owns the filing status and updates it from the `analysis.*` events.
Reporting builds the report from the terminal events.

## Consequences
- Fewer moving parts: there is no orchestrator to deploy, scale or keep available.
- Services know only the events, not each other.
- The flow is implicit. It is spread over the listeners and documented in
  [architecture.md](../architecture.md). Following one filing needs the shared `correlationId` in
  the logs.
- No component owns timeouts or compensation. A filing stuck in `SUBMITTED` (for example after a
  poison `filing.submitted`) is not detected automatically. With a longer flow or compensation steps,
  an orchestrated saga would be the better choice.

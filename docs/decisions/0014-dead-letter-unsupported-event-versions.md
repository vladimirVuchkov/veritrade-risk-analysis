# 0014 - Dead-letter events with an unsupported version or an over-long text

## Context
Every event carries `eventVersion` (currently 1). Before Wave 3 only Reporting dead-lettered a newer
version. Ingestion and Analysis read the fields they knew, so a version 2 `analysis.completed` gave a
`COMPLETED` filing without a report: the services disagreed about the same event. Ingestion also cut
an over-long failure reason itself instead of rejecting it.

## Decision
The contract has one rule for every consumer, in
[`messaging-topology.md`](../contracts/messaging-topology.md) ("Event versioning" and "Text limits"):

- A consumer that receives an `eventVersion` above the versions it supports sends the message to its
  dead-letter queue without retries. Analysis (`FilingSubmittedReader`), Ingestion
  (`AnalysisEventReader`) and Reporting all apply it.
- Producers never send a text over its limit (UTF-16 code units). Analysis cuts the `analysis.failed`
  reason to 1000 units without splitting a surrogate pair, keeps an excerpt within 1000 units and a
  matched text within 500. A consumer that receives an over-limit text dead-letters the message.

## Consequences
- A newer event is neither processed by some services nor lost: it waits in a `.dlq` and can be moved
  back after the consumer is upgraded.
- All services agree on the outcome of one event.
- Until the upgrade the filing stays where it was, and someone must look at the dead-letter queues.
  There is no replay tool.
- A breaking change still needs a rollout in which consumers support the old and the new version.

# 0002 - A database per service (file-mode H2)

## Context
Services that share a database are coupled through its schema. The prototype must also start with
one command and without an external database server.

## Decision
Each service that keeps state owns a file-mode H2 database on its own Docker volume: Ingestion
(`filings`, `outbox`) and Reporting (`reports`, `findings`, `processed_events`). Analysis keeps no
state. Flyway creates the schemas (`V1__init.sql`) and Hibernate only validates them
(`ddl-auto: validate`). H2 is pinned to 2.3.232, because in 2.4.240 a CHECK constraint fails every
insert once the connection that created it has been closed.

## Consequences
- A service can change its schema without touching the others.
- There are no cross-service queries. The UI reads the status from Ingestion and the report from
  Reporting, and the two can briefly disagree.
- H2 is not a production database. Production would use PostgreSQL, with one database or schema per
  service.

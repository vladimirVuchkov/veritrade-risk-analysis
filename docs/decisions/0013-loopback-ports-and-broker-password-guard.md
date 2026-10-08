# 0013 - Publish on loopback by default and guard the default broker password

## Context
`docker compose up --build` must work from a fresh clone with no manual step, so the broker has a
built-in default user (`veritrade`/`veritrade`). Before Wave 3 the UI and the RabbitMQ management UI
were published on every host interface, so that known password was reachable from the network. The
UI has no authentication.

## Decision
- Both published ports are bound to `BIND_ADDRESS`, default `127.0.0.1`
  ([`docker-compose.yml`](../../docker-compose.yml), [`.env.example`](../../.env.example)).
- The default credentials stay. RabbitMQ is built from
  [`infra/docker/rabbitmq.Dockerfile`](../../infra/docker/rabbitmq.Dockerfile), which runs
  [`infra/rabbitmq/credentials-guard.sh`](../../infra/rabbitmq/credentials-guard.sh) before the
  official entrypoint:
  - a known weak password (`veritrade`, `change-me`, `guest` or empty) with a loopback
    `BIND_ADDRESS` (`127.*`, `::1`, `localhost`) starts the broker with a `WARNING` line;
  - the same password with any other `BIND_ADDRESS` stops the container with an `ERROR` line, so
    `docker compose up --wait` fails.
- `scripts/smoke.sh` and `scripts/chaos.sh` print a warning when they run with a weak password.

## Consequences
- A fresh clone still starts with one command, and nothing is reachable from other machines.
- Publishing beyond loopback requires a real `RABBITMQ_PASSWORD`. The password is stored in the
  `rabbitmq-data` volume on first start, so changing it needs `docker compose down -v`.
- The weak-password list is fixed; any other password counts as strong.
- A non-loopback UI still has no authentication; choosing that address is the operator's decision.
- The warning is in the container log, not in the output of `docker compose up -d`.
- `scripts/smoke.sh` and `PublishedPortsE2E` check the bindings and the warning.

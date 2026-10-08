#!/bin/sh
# Entrypoint wrapper of the RabbitMQ image: checks the broker credentials before the official
# entrypoint starts the server.
#   - A known weak password (the built-in default, the .env.example placeholder, guest or empty) is
#     accepted only while the published ports are bound to a loopback address, and a warning is logged.
#   - The same weak password with the ports published on any other address stops the container.
# "credentials-guard.sh check" runs only the check (exit 0 or 1); scripts/smoke.sh uses it.
set -eu

bind_address="${VERITRADE_BIND_ADDRESS:-127.0.0.1}"
password="${RABBITMQ_DEFAULT_PASS:-}"

is_weak_password() {
    case "$password" in
        ""|veritrade|change-me|guest) return 0 ;;
        *) return 1 ;;
    esac
}

is_loopback() {
    case "$bind_address" in
        127.*|::1|localhost) return 0 ;;
        *) return 1 ;;
    esac
}

if is_weak_password; then
    if ! is_loopback; then
        echo "ERROR: the RabbitMQ password is a known default and the ports are published on" \
             "'$bind_address'. Set a strong RABBITMQ_PASSWORD in .env (then docker compose down -v)," \
             "or keep BIND_ADDRESS=127.0.0.1." >&2
        exit 1
    fi
    echo "WARNING: RabbitMQ runs with a known default password. This is acceptable only for local use:" \
         "the ports are published on $bind_address only. Set RABBITMQ_PASSWORD in .env for anything else." >&2
fi

if [ "${1:-}" = "check" ]; then
    exit 0
fi
exec docker-entrypoint.sh "$@"

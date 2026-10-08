# syntax=docker/dockerfile:1.7
# The official RabbitMQ image with a credentials check in front of its entrypoint
# (see infra/rabbitmq/credentials-guard.sh). The build context is infra/rabbitmq.
FROM rabbitmq:4.3-management-alpine
COPY --chmod=755 credentials-guard.sh /usr/local/bin/credentials-guard.sh
ENTRYPOINT ["credentials-guard.sh"]
CMD ["rabbitmq-server"]

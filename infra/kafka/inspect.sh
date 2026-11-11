#!/bin/bash
# Inspección replicable del clúster Kafka de Pedidos360: brokers, tópicos con sus configuraciones y
# consumer groups con su lag por partición. Uso (con el compose levantado):
#   docker compose -f compose.kafka.yml run --rm --entrypoint bash kafka-init /opt/pedidos360/inspect.sh
# o dentro de cualquier broker: bash inspect.sh (KAFKA_BOOTSTRAP por defecto: kafka-1:19092).
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka-1:19092}"

echo "== Brokers"
kafka-broker-api-versions --bootstrap-server "$BOOTSTRAP" | grep -E '^[^ ].*\(id:' || true

echo
echo "== Tópicos (particiones, réplicas, ISR y configuraciones propias)"
kafka-topics --bootstrap-server "$BOOTSTRAP" --describe --exclude-internal

echo
echo "== Configuración efectiva de los tópicos de negocio"
for topic in $(kafka-topics --bootstrap-server "$BOOTSTRAP" --list --exclude-internal); do
  echo "-- $topic"
  kafka-configs --bootstrap-server "$BOOTSTRAP" --entity-type topics --entity-name "$topic" --describe
done

echo
echo "== Consumer groups (miembros, offset confirmado, último offset y LAG por partición)"
kafka-consumer-groups --bootstrap-server "$BOOTSTRAP" --describe --all-groups || echo "(sin consumer groups)"

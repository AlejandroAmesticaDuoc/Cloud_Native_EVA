#!/bin/bash
# Crea (o actualiza) los tópicos de Pedidos360 con la configuración de cada flujo de datos.
# Es idempotente: se puede ejecutar varias veces. Si el tópico existe, solo reaplica sus configuraciones.
# Variables: KAFKA_BOOTSTRAP (obligatoria), KAFKA_ORDERS_TOPIC y KAFKA_TIMELINE_TOPIC (opcionales).
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:?Define KAFKA_BOOTSTRAP}"
ORDERS_TOPIC="${KAFKA_ORDERS_TOPIC:-orders.events}"
TIMELINE_TOPIC="${KAFKA_TIMELINE_TOPIC:-audit.timeline}"
REPLICATION="${KAFKA_TOPIC_REPLICATION:-3}"
MIN_ISR="${KAFKA_TOPIC_MIN_ISR:-2}"

# nombre | particiones | cleanup.policy | retention.ms | descripción
TOPICS=(
  "${ORDERS_TOPIC}|3|delete|604800000|eventos de pedidos (7 días): fuente para Audit y Report"
  "${TIMELINE_TOPIC}|3|compact,delete|2592000000|línea de tiempo de auditoría (30 días, último estado por pedido)"
  "${ORDERS_TOPIC}.audit.DLT|3|delete|1209600000|eventos que Audit no pudo procesar (14 días)"
  "${ORDERS_TOPIC}.report.DLT|3|delete|1209600000|eventos que Report no pudo procesar (14 días)"
  "${TIMELINE_TOPIC}.report.DLT|3|delete|1209600000|entradas de la línea de tiempo que Report no pudo procesar (14 días)"
)

for entry in "${TOPICS[@]}"; do
  IFS='|' read -r name partitions cleanup retention description <<< "$entry"
  echo "Tópico ${name}: ${description}"
  kafka-topics --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$name" \
    --partitions "$partitions" --replication-factor "$REPLICATION" \
    --config "cleanup.policy=${cleanup}" --config "retention.ms=${retention}" \
    --config "min.insync.replicas=${MIN_ISR}"
  # Reaplica la configuración por si el tópico ya existía con otros valores.
  kafka-configs --bootstrap-server "$BOOTSTRAP" --entity-type topics --entity-name "$name" --alter \
    --add-config "cleanup.policy=[${cleanup}],retention.ms=${retention},min.insync.replicas=${MIN_ISR}"
done

kafka-topics --bootstrap-server "$BOOTSTRAP" --describe --topics-with-overrides

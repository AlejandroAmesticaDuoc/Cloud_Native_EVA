#!/usr/bin/env bash
set -e

: "${ORDERS_DB_PASSWORD:?Falta la contraseña del usuario de Orders}"

psql --set=ON_ERROR_STOP=1 \
    --username "$POSTGRES_USER" \
    --dbname "$POSTGRES_DB" \
    --set=orders_password="$ORDERS_DB_PASSWORD" <<'SQL'
CREATE ROLE pedidos360_orders
    LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    PASSWORD :'orders_password';

CREATE DATABASE pedidos360_orders OWNER pedidos360_orders;
REVOKE ALL ON DATABASE pedidos360_orders FROM PUBLIC;
GRANT CONNECT ON DATABASE pedidos360_orders TO pedidos360_orders;

\connect pedidos360_orders
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
SQL

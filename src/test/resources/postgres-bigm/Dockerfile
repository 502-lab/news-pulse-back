# Stage 1: build pg_bigm 1.2 from source against PostgreSQL 16
FROM postgres:16 AS builder

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       build-essential \
       postgresql-server-dev-16 \
       libicu-dev \
       ca-certificates \
       wget \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
RUN wget -qO pg_bigm.tar.gz \
       "https://github.com/pgbigm/pg_bigm/archive/refs/tags/v1.2-20240606.tar.gz" \
    && tar xzf pg_bigm.tar.gz \
    && cd pg_bigm-1.2-20240606 \
    && make USE_PGXS=1 install

# Stage 2: clean postgres image with pg_bigm artifacts copied in
FROM postgres:16

COPY --from=builder \
    /usr/lib/postgresql/16/lib/pg_bigm.so \
    /usr/lib/postgresql/16/lib/pg_bigm.so

COPY --from=builder \
    /usr/share/postgresql/16/extension/pg_bigm.control \
    /usr/share/postgresql/16/extension/pg_bigm--1.2.sql \
    /usr/share/postgresql/16/extension/

CMD ["postgres", "-c", "shared_preload_libraries=pg_bigm"]

CREATE TABLE products
(
    id         UUID PRIMARY KEY,
    sku        VARCHAR(30)    NOT NULL UNIQUE,
    name       VARCHAR(200)   NOT NULL,
    category   VARCHAR(50)    NOT NULL,
    price      NUMERIC(12, 2) NOT NULL CHECK (price > 0),
    views      BIGINT         NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ    NOT NULL,
    version    BIGINT         NOT NULL DEFAULT 0
);

CREATE INDEX ix_products_category ON products (category, name);

-- Write-behind batches already applied: makes a re-run of a flush harmless.
CREATE TABLE view_flushes
(
    batch_id   VARCHAR(64) PRIMARY KEY,
    applied_at TIMESTAMPTZ NOT NULL
);

-- 500 demo products in 5 categories.
INSERT INTO products (id, sku, name, category, price, updated_at)
SELECT gen_random_uuid(),
       'SKU-' || lpad(n::text, 5, '0'),
       (ARRAY ['Keyboard', 'Mouse', 'Monitor', 'Headset', 'Webcam'])[1 + n % 5] || ' model ' || n,
       (ARRAY ['keyboards', 'mice', 'monitors', 'audio', 'video'])[1 + n % 5],
       round((10 + random() * 490)::numeric, 2),
       now()
FROM generate_series(1, 500) AS n;

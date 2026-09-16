CREATE TABLE IF NOT EXISTS dataset_meta (
 id INT PRIMARY KEY, data_version VARCHAR(80) NOT NULL, start_date DATE NOT NULL,
 end_date DATE NOT NULL, updated_at VARCHAR(40) NOT NULL
);
CREATE TABLE IF NOT EXISTS biz_orders (
 id VARCHAR(40) PRIMARY KEY, region VARCHAR(30) NOT NULL, category VARCHAR(30) NOT NULL,
 channel VARCHAR(30) NOT NULL, created_on DATE NOT NULL, paid_on DATE,
 status VARCHAR(20) NOT NULL, paid_cents BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS refunds (
 id VARCHAR(40) PRIMARY KEY, order_id VARCHAR(40) NOT NULL, refunded_on DATE NOT NULL,
 amount_cents BIGINT NOT NULL, FOREIGN KEY (order_id) REFERENCES biz_orders(id)
);
CREATE TABLE IF NOT EXISTS analysis_reports (
 id VARCHAR(36) PRIMARY KEY, owner_name VARCHAR(40) NOT NULL, question_text VARCHAR(1000) NOT NULL,
 data_version VARCHAR(80) NOT NULL, created_at VARCHAR(40) NOT NULL, report_json LONGTEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS analysis_audit (
 id VARCHAR(36) PRIMARY KEY, owner_name VARCHAR(40) NOT NULL, operation VARCHAR(30) NOT NULL,
 outcome VARCHAR(40) NOT NULL, duration_ms BIGINT NOT NULL, sql_count INT NOT NULL,
 created_at VARCHAR(40) NOT NULL
);
CREATE OR REPLACE VIEW v_finance_events AS
 SELECT paid_on AS biz_date, region, category, channel, status,
 paid_cents, CAST(0 AS SIGNED) AS refund_cents
 FROM biz_orders WHERE paid_on IS NOT NULL
 UNION ALL
 SELECT r.refunded_on AS biz_date, o.region, o.category, o.channel, o.status,
 CAST(0 AS SIGNED) AS paid_cents, r.amount_cents AS refund_cents
 FROM refunds r JOIN biz_orders o ON o.id=r.order_id;
CREATE OR REPLACE VIEW v_order_cohorts AS
 SELECT created_on AS biz_date, region, category, channel, status, 1 AS order_count,
 CASE WHEN status='CANCELLED' THEN 1 ELSE 0 END AS cancelled_count,
 CASE WHEN paid_on IS NOT NULL THEN 1 ELSE 0 END AS paid_count,
 CASE WHEN status='FULFILLED' THEN 1 ELSE 0 END AS fulfilled_count
 FROM biz_orders;

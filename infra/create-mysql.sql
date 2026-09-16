-- 使用管理员账号执行一次，建立独立演示数据库。
CREATE DATABASE IF NOT EXISTS metrics_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'metrics'@'localhost' IDENTIFIED BY 'local-metrics-pass';
GRANT ALL PRIVILEGES ON metrics_agent.* TO 'metrics'@'localhost';

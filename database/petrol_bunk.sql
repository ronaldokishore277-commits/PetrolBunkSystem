-- Automated Fuel Management System
-- MySQL 8.x
-- Run this entire script in MySQL Workbench or the MySQL command-line client.

CREATE DATABASE IF NOT EXISTS petrol_bunk
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE petrol_bunk;

-- One row per fuel type. The application updates current_volume after dispensing.
CREATE TABLE IF NOT EXISTS tanks (
    fuel_type VARCHAR(20) NOT NULL PRIMARY KEY,
    current_volume DECIMAL(12,2) NOT NULL,
    max_capacity DECIMAL(12,2) NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_tank_volume CHECK (current_volume >= 0),
    CONSTRAINT chk_tank_capacity CHECK (max_capacity > 0)
) ENGINE=InnoDB;

-- Seed rows are required because the Java application performs UPDATE by fuel_type.
INSERT INTO tanks (fuel_type, current_volume, max_capacity)
VALUES
    ('Petrol', 10000.00, 10000.00),
    ('Diesel', 10000.00, 10000.00)
ON DUPLICATE KEY UPDATE
    max_capacity = VALUES(max_capacity);

CREATE TABLE IF NOT EXISTS transactions (
    transaction_id VARCHAR(8) NOT NULL PRIMARY KEY,
    fuel_type VARCHAR(20) NOT NULL,
    volume DECIMAL(12,2) NOT NULL,
    total_cost DECIMAL(14,2) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_transaction_volume CHECK (volume > 0),
    CONSTRAINT chk_transaction_cost CHECK (total_cost >= 0),
    INDEX idx_transactions_created_at (created_at),
    INDEX idx_transactions_fuel_type (fuel_type)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS shift_settlements (
    settlement_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    cashier_name VARCHAR(120) NOT NULL,
    total_collections DECIMAL(14,2) NOT NULL,
    daily_expenses DECIMAL(14,2) NOT NULL,
    net_profit DECIMAL(14,2) NOT NULL,
    settled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_settlements_settled_at (settled_at)
) ENGINE=InnoDB;

-- Useful verification queries:
SELECT * FROM tanks;
SELECT * FROM transactions ORDER BY created_at DESC;
SELECT * FROM shift_settlements ORDER BY settled_at DESC;

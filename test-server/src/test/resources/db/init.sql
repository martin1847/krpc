-- TESTCONTAINERS-001: schema for the DB-backed test-server tests.
-- Mirrors the columns test-server/src/main/resources/mapper/UserMapper.xml reads and writes.
CREATE TABLE users (
    id   INT          NOT NULL PRIMARY KEY,
    name VARCHAR(128) NOT NULL
);

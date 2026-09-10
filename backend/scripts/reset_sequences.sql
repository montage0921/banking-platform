-- Advance every identity sequence past the highest explicitly-seeded primary key.
--
-- seed_demo_data.sql inserts explicit primary keys (customers 900001-900100,
-- savings goals 900001-900089). PostgreSQL does not advance an identity
-- column's sequence when an explicit value is supplied, so without this the
-- next application-generated insert collides with a seeded row.
--
-- Run this immediately after seed_demo_data.sql. It is idempotent and safe to
-- run against an unseeded database.
--
-- Tables whose ids the application assigns itself (account, bank_transaction,
-- gic_investment, standing_orders, pending_agent_actions, notification_decisions,
-- idempotency_record) have no sequence and are skipped automatically.

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.table_name,
               c.column_name,
               pg_get_serial_sequence(quote_ident(c.table_name), c.column_name) AS seq
        FROM information_schema.columns c
        WHERE c.table_schema = 'public'
          AND pg_get_serial_sequence(quote_ident(c.table_name), c.column_name) IS NOT NULL
    LOOP
        EXECUTE format(
            'SELECT setval(%L, COALESCE((SELECT MAX(%I) FROM %I), 0) + 1, false)',
            r.seq, r.column_name, r.table_name);
        RAISE NOTICE 'reset sequence % for %.%', r.seq, r.table_name, r.column_name;
    END LOOP;
END $$;

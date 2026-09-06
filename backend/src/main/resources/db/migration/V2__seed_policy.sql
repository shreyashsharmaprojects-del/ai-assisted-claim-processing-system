-- One seeded policy row so the walking skeleton page has real DB data to show.
-- More seeded policies arrive when FNOL (slice 1) needs matchable rows.

INSERT INTO policy (policy_number, product_code, holder_name, holder_email, coverage)
VALUES ('POL-10001', 'HOME', 'Ada Lovelace', 'ada.lovelace@example.test', '{"type":"home","sum_insured":500000}'::jsonb);

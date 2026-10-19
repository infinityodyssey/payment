-- Machine accounts that can request a token: partners (VENDOR) and internal tools (ADMIN).
--
--   GRANT SELECT ON TVSCS_ONLINE_USER_PG TO PAYMENT_APP;
--
-- SELECT ONLY. The application never writes to this table. Onboarding a partner is a DBA
-- action with a change record behind it, which is what you want to show an auditor.
--
-- THERE IS NO "LAST TOKEN AT" COLUMN, on purpose. It would mean an UPDATE on the same row
-- for every token request, and a partner that fetches a token per call rather than
-- caching it would have six servers queueing on one row lock — at the month-end rate
-- that is hundreds of serialised writes a minute to answer a question nobody asks in
-- real time.
--
-- Token issue writes no audit row either, so the ONLY record of partner authentication
-- is the application log, in Loki:
--
--   {app="auth-service"} |= "Token issued"            -- successes
--   {app="auth-service"} |= "Token request refused"   -- failures; alert on the rate
--
-- That makes Loki retention load-bearing. If it is not actually configured to the
-- period you have committed to, there is no record anywhere that a partner ever
-- authenticated.
--
-- PASSWORD HOLDS A BCRYPT HASH, NOT A PASSWORD. The column name is the business name;
-- the content is a hash at cost 12 or higher. A plaintext value here means one SELECT
-- grant, one export or one developer with read access walks away with every partner's
-- credential — and because partners reuse secrets across systems, the damage does not
-- stop at this platform.
--
-- Generate a hash:
--   htpasswd -bnBC 12 "" 'the-secret' | tr -d ':\n' | sed 's/^\$2y/\$2a/'

CREATE TABLE TVSCS_ONLINE_USER_PG (
    USERNAME       VARCHAR2(64)  NOT NULL,
    PASSWORD       VARCHAR2(100) NOT NULL,
    ACTIVE         CHAR(1)       DEFAULT 'Y' NOT NULL,
    ROLE           VARCHAR2(16)  NOT NULL,
    CREATED_AT     TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT PK_ONLINE_USER_PG PRIMARY KEY (USERNAME),
    CONSTRAINT CK_ONLINE_USER_ACTIVE CHECK (ACTIVE IN ('Y','N')),
    -- The role set is enforced in the database as well as in code. A typo inserted here
    -- would otherwise produce a token carrying a role that authorises nothing, which
    -- presents as "the partner's integration stopped working" days after the insert.
    CONSTRAINT CK_ONLINE_USER_ROLE CHECK (ROLE IN ('ADMIN','VENDOR')),
    -- 60 characters is a bcrypt hash. Anything shorter is almost certainly a plaintext
    -- password someone inserted by hand, and this stops it reaching production.
    CONSTRAINT CK_ONLINE_USER_PWD_HASHED CHECK (LENGTH(PASSWORD) >= 60)
);

COMMENT ON TABLE  TVSCS_ONLINE_USER_PG            IS 'Machine accounts for partner/admin token issue';
COMMENT ON COLUMN TVSCS_ONLINE_USER_PG.PASSWORD   IS 'BCrypt hash, cost 12+. Never a plaintext password.';
COMMENT ON COLUMN TVSCS_ONLINE_USER_PG.ACTIVE     IS 'Y/N. Checked at every token issue. N stops NEW tokens; it does not revoke one already issued.';
COMMENT ON COLUMN TVSCS_ONLINE_USER_PG.ROLE       IS 'ADMIN or VENDOR. The only source of the role in the JWT.';

-- Example. Replace the hash before running anywhere real.
-- INSERT INTO TVSCS_ONLINE_USER_PG (USERNAME, PASSWORD, ACTIVE, ROLE)
-- VALUES ('acme_vendor', '$2a$12$replace.this.with.a.real.bcrypt.hash.value.here.ok', 'Y', 'VENDOR');

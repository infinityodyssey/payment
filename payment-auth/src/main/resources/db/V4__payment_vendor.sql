-- Approved payment vendors. The backend is the only source of which vendors exist and
-- which are usable; the frontend renders what this table allows and nothing else.
--
--   GRANT SELECT ON TVSCS_PAYMENT_VENDOR_PG TO PAYMENT_APP;
--
-- SELECT only. Enabling or disabling a vendor is a DBA action, which is what you want
-- when "turn Paytm off, they are down" has to happen at 11pm with a record of who did it.
--
-- NOT CACHED IN THE APPLICATION, on purpose, and this is the opposite call from the token
-- endpoint. A primary-key read on a ten-row table is served from Oracle's buffer cache in
-- microseconds, so caching buys nothing measurable — and it costs the thing that matters
-- most here: disabling a vendor takes effect on the very next request, not after a cache
-- TTL. During an outage that difference is the whole point.
--
-- TVS_PREFIX is the first two characters of every tvstxnid generated for this vendor.
-- It lives here rather than in code because payment-service generates the id and the
-- reconciliation job parses it back; one table, one answer.

CREATE TABLE TVSCS_PAYMENT_VENDOR_PG (
    VENDOR_CODE    VARCHAR2(32)  NOT NULL,
    VENDOR_NAME    VARCHAR2(64)  NOT NULL,
    TVS_PREFIX     CHAR(2)       NOT NULL,
    ACTIVE         CHAR(1)       DEFAULT 'Y' NOT NULL,
    -- Rupees. A vendor that rejects anything under 1 rupee or over 2 lakh should say so
    -- here rather than failing the borrower at the vendor's own checkout page.
    MIN_AMOUNT     NUMBER(15,2)  DEFAULT 1 NOT NULL,
    MAX_AMOUNT     NUMBER(15,2),
    -- Display order in the vendor list. Lower first.
    PRIORITY       NUMBER(5)     DEFAULT 100 NOT NULL,
    CREATED_AT     TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT PK_PAYMENT_VENDOR_PG PRIMARY KEY (VENDOR_CODE),
    CONSTRAINT UQ_PAYMENT_VENDOR_PREFIX UNIQUE (TVS_PREFIX),
    CONSTRAINT CK_PAYMENT_VENDOR_ACTIVE CHECK (ACTIVE IN ('Y','N')),
    CONSTRAINT CK_PAYMENT_VENDOR_AMOUNT CHECK (MAX_AMOUNT IS NULL OR MAX_AMOUNT >= MIN_AMOUNT)
);

COMMENT ON TABLE  TVSCS_PAYMENT_VENDOR_PG             IS 'Approved payment vendors. Backend is the only source of this list.';
COMMENT ON COLUMN TVSCS_PAYMENT_VENDOR_PG.ACTIVE      IS 'Y/N. Takes effect on the next request — nothing caches this.';
COMMENT ON COLUMN TVSCS_PAYMENT_VENDOR_PG.TVS_PREFIX  IS 'First two characters of tvstxnid for this vendor. Unique.';

-- Prefixes as agreed in the architecture. Confirm against what the vendors expect before
-- go-live: changing one later invalidates reconciliation against historic transactions.
INSERT INTO TVSCS_PAYMENT_VENDOR_PG (VENDOR_CODE, VENDOR_NAME, TVS_PREFIX, ACTIVE, MIN_AMOUNT, MAX_AMOUNT, PRIORITY)
VALUES ('PAYTM', 'Paytm', 'PT', 'Y', 1, 200000, 10);
INSERT INTO TVSCS_PAYMENT_VENDOR_PG (VENDOR_CODE, VENDOR_NAME, TVS_PREFIX, ACTIVE, MIN_AMOUNT, MAX_AMOUNT, PRIORITY)
VALUES ('ATOM', 'Atom', 'AT', 'Y', 1, 200000, 20);
INSERT INTO TVSCS_PAYMENT_VENDOR_PG (VENDOR_CODE, VENDOR_NAME, TVS_PREFIX, ACTIVE, MIN_AMOUNT, MAX_AMOUNT, PRIORITY)
VALUES ('AIRTEL', 'Airtel Payments Bank', 'AR', 'Y', 1, 200000, 30);
INSERT INTO TVSCS_PAYMENT_VENDOR_PG (VENDOR_CODE, VENDOR_NAME, TVS_PREFIX, ACTIVE, MIN_AMOUNT, MAX_AMOUNT, PRIORITY)
VALUES ('UPI_QR', 'UPI QR Code', 'QR', 'Y', 1, 100000, 40);
COMMIT;

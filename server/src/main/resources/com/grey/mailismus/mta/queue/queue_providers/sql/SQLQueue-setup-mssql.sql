CREATE TABLE MMTA_SMTPQUEUE (
	QID int IDENTITY (1,1) NOT NULL,
	SPID int NOT NULL,
	MAILADDR_FROM varchar(255) NULL,
	DOMAIN_TO varchar(255) NULL,
	MAILBOX_TO varchar(255) NOT NULL,  --  local-name part only - combine with DOMAIN_TO to form conceptual MAILADDR_TO
	IPRECV int NOT NULL default(0),
	RECVTIME bigint NOT NULL,  -- JVM system time in milliseconds
	NEXTSEND bigint NULL,  -- JVM system time in milliseconds - Null means message has been declared a bounce and will not be retried
	STATUS smallint NULL,       -- SMTP status code of last attempt
	RETRYCOUNT smallint NOT NULL default(0)  -- strictly speaking, this is the Try count, not the Retry count
)
_END_

ALTER TABLE MMTA_SMTPQUEUE ADD CONSTRAINT
	PK_MMTA_SMTPQUEUE PRIMARY KEY (QID)
_END_

CREATE INDEX IX_MMTA_SMTPQUEUE_NEXTSEND
	ON MMTA_SMTPQUEUE (NEXTSEND)
_END_

CREATE INDEX IX_MMTA_SMTPQUEUE_SPID
	ON MMTA_SMTPQUEUE (SPID)
_END_
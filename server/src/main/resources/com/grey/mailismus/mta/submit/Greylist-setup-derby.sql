-- Only difference from standard script is the lack of the VARBINARY SQL type
CREATE TABLE MMTA_GREYLIST (
	ID int GENERATED BY DEFAULT AS IDENTITY (START WITH 1), -- NB: have verified this does not work for MS-SQL
	CREATED bigint NOT NULL,
	IP_FROM int NOT NULL,
	MAILADDR_FROM varchar(255) for bit data,
	MAILADDR_TO varchar(255) for bit data NOT NULL,
	QTINE_TILL bigint,  -- JVM system time in milliseconds, null means not quarantined
	LAST_RECV bigint  -- JVM system time in milliseconds, null means no approved messages yet
)
_END_

ALTER TABLE MMTA_GREYLIST ADD CONSTRAINT
	PK_MMTA_GREYLIST PRIMARY KEY (ID)
_END_

CREATE UNIQUE INDEX IX_MMTA_GREYLIST_FROMTO  -- this is a unique index
	ON MMTA_GREYLIST (IP_FROM, MAILADDR_FROM, MAILADDR_TO)
_END_
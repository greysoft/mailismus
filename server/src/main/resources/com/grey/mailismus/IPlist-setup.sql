DROP TABLE _TBLNAM_
_END_

CREATE TABLE _TBLNAM_ (
	IP int NOT NULL,
	UPDATED bigint
)
_END_

ALTER TABLE _TBLNAM_ ADD CONSTRAINT
	PK__TBLNAM_ PRIMARY KEY (IP)
_END_

CREATE INDEX IX__TBLNAM__UPDATED
	ON _TBLNAM_ (UPDATED)
_END_
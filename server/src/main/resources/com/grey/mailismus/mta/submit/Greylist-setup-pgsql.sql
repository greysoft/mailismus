/*
 * PL/pgSQL may need to be installed. Run this to check:
 *		SELECT true FROM pg_catalog.pg_language WHERE lanname = 'plpgsql';
 * If the result row has the value true, PL/pgSQL is installed, else run this command at Unix shell
 *		createlang plpgsql mailismus (where 'mailismus' is an example database name)
 * May need to be logged in as 'postgres' user for authentication to work
 */
CREATE TABLE MMTA_GREYLIST (
	ID serial NOT NULL,
	CREATED bigint NOT NULL,
	IP_FROM int NOT NULL,
	MAILADDR_FROM bytea,
	MAILADDR_TO bytea NOT NULL,
	QTINE_TILL bigint,  -- JVM system time in milliseconds, null means not quarantined
	LAST_RECV bigint  -- JVM system time in milliseconds
)
WITHOUT OIDS
_END_

ALTER TABLE MMTA_GREYLIST ADD CONSTRAINT
	PK_MMTA_GREYLIST PRIMARY KEY (ID)
_END_

CREATE UNIQUE INDEX IX_MMTA_GREYLIST_FROMTO  -- this is a unique index
	ON MMTA_GREYLIST (IP_FROM, MAILADDR_FROM, MAILADDR_TO)
_END_


-- Can be run manually like this: select MMTA_GREYLIST_VET(101, null, 'recip2@domain'::bytea, 95, 5, 10)
CREATE OR REPLACE FUNCTION MMTA_GREYLIST_VET(int, bytea, bytea, bigint, bigint, bigint)
RETURNS int AS '
DECLARE
	ipfrom ALIAS FOR $1;
	addrfrom ALIAS FOR $2;
	addrto ALIAS FOR $3;
	systime ALIAS FOR $4;
	qtine_interval ALIAS FOR $5;
	update_maxfreq ALIAS FOR $6;
	idval int := 0;
	qtine bigint;
	lastrecv bigint;
BEGIN
	SELECT INTO idval, qtine, lastrecv
		ID, QTINE_TILL, LAST_RECV
		FROM MMTA_GREYLIST
		WHERE IP_FROM = ipfrom
			AND ((addrfrom IS NULL AND MAILADDR_FROM IS NULL) OR (addrfrom IS NOT NULL AND MAILADDR_FROM = addrfrom))
			AND MAILADDR_TO = addrto;

	IF idval IS NULL OR idval = 0 THEN
		INSERT INTO MMTA_GREYLIST (IP_FROM, CREATED, MAILADDR_FROM, MAILADDR_TO, QTINE_TILL)
			VALUES (ipfrom, systime, addrfrom, addrto, systime + qtine_interval);
	ELSE
		IF qtine IS null THEN
			IF lastrecv + @update_maxfreq <= systime THEN
				UPDATE MMTA_GREYLIST
					SET LAST_RECV = systime
					WHERE ID = idval;
			END IF;
		ELSE
			IF qtine > @systime THEN
				idval := 0; -- still quarantined
			ELSE
				-- move this entry out of quarantine
				UPDATE MMTA_GREYLIST
					SET QTINE_TILL = null, LAST_RECV = systime
					WHERE ID = idval;
			END IF;
		END IF;
	END IF;

	IF idval IS NULL THEN idval = 0; END IF;
	RETURN idval;
END'
LANGUAGE plpgsql
_END_

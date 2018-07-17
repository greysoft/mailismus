-- Same table def as standard script apart from IDENTITY syntax. Also, this script defines a stored procedure
CREATE TABLE MMTA_GREYLIST (
	ID int IDENTITY (1,1) NOT NULL,
	CREATED bigint NOT NULL,
	IP_FROM int NOT NULL,
	MAILADDR_FROM varbinary(255),
	MAILADDR_TO varbinary(255) NOT NULL,
	QTINE_TILL bigint,  -- JVM system time in milliseconds, null means not quarantined
	LAST_RECV bigint  -- JVM system time in milliseconds
)
_END_

ALTER TABLE MMTA_GREYLIST ADD CONSTRAINT
	PK_MMTA_GREYLIST PRIMARY KEY (ID)
_END_

CREATE UNIQUE INDEX IX_MMTA_GREYLIST_FROMTO  -- this is a unique index
	ON MMTA_GREYLIST (IP_FROM, MAILADDR_FROM, MAILADDR_TO)
_END_


DROP PROCEDURE MMTA_GREYLIST_VET
_END_

CREATE PROCEDURE MMTA_GREYLIST_VET
(
	@ipfrom int,
	@addrfrom varbinary(255),
	@addrto varbinary(255),
	@systime bigint,
	@qtine_interval bigint,
	@update_maxfreq bigint
)
AS
BEGIN
	SET NOCOUNT ON
	declare @idval int
	declare @qtine bigint
	declare @lastrecv bigint

	SET @idval = 0
	SELECT @idval = ID, @qtine = QTINE_TILL, @lastrecv = LAST_RECV
		FROM MMTA_GREYLIST
		WHERE IP_FROM = @ipfrom
			AND ((@addrfrom IS NULL AND MAILADDR_FROM IS NULL) OR (@addrfrom IS NOT NULL AND MAILADDR_FROM = @addrfrom))
			AND MAILADDR_TO = @addrto

	IF @idval = 0 OR @idval IS NULL
	BEGIN
		INSERT INTO MMTA_GREYLIST (IP_FROM, CREATED, MAILADDR_FROM, MAILADDR_TO, QTINE_TILL)
			VALUES (@ipfrom, @systime, @addrfrom, @addrto, @systime + @qtine_interval)
	END
	ELSE
	BEGIN
		IF @qtine IS null
		BEGIN
			IF @lastrecv + @update_maxfreq <= @systime
			BEGIN
				UPDATE MMTA_GREYLIST
					SET LAST_RECV = @systime
					WHERE ID = @idval
			END
		END
		ELSE
		BEGIN
			IF @qtine > @systime
			BEGIN
				SET @idval = 0 -- still quarantined
			END
			ELSE
			BEGIN
				-- move this entry out of quarantine
				UPDATE MMTA_GREYLIST
					SET QTINE_TILL = null, LAST_RECV = @systime
					WHERE ID = @idval
			END
		END
	END

	-- Can only get back a "RESULT" column for Postgresql (unless we use cursors?) so align our return value with that
	SELECT @idval AS RESULT
END
_END_

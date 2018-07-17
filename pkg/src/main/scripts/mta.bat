:: Copyright 2010-2018 Yusef Badri - All rights reserved.
:: Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).

@ECHO OFF
set CMD=%1
set MTAJAR=mailismus-server-${project.version}.jar
set NAFCFG=conf\naf.xml
set JVM="%JAVA_HOME%"\bin\java

if "%JAVA_HOME%" == "" (
	set JVM=java
)

if "%CMD%" == "" (
	echo Specify "start" to boot the MTA, or else a NAFMAN command followed by its parameters
	goto :eof
)

if "%CMD%" == "start" (
	%JVM% -jar lib\%MTAJAR% -c %NAFCFG% >>mta-boot.log
	goto :eof
)

:: USAGE: bin\mta.bat cmd-name [-nocheck] [cmd-args]
:: Eg. bin\mta.bat stop
%JVM% -jar lib\%MTAJAR% -c %NAFCFG% -cmd %CMD% %2 %3 %4 %5 %6

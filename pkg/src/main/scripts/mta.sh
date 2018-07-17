#!/bin/sh
# Copyright 2010-2018 Yusef Badri - All rights reserved.
# Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).

cd `dirname $0`/..
CMD=$1
MTAJAR=mailismus-server-${project.version}.jar
NAFCFG=conf/naf.xml

JVM="$JAVA_HOME"/bin/java
if [ ! -x "$JVM" ]; then JVM=java; fi

osname6=`uname | cut -c1-6 | tr '[:upper:]' '[:lower:]'`

case "$CMD" in
	"")
	echo 'Specify "start" to boot the MTA, or else a NAFMAN command followed by its parameters'
	;;

    start)
	echo "Starting Mailismus MTA ... $JVM"
	#if [ ! "$osname6" = "cygwin" ]; then ulimit -n 999999; fi
	umask 077
	"$JVM" -jar lib/$MTAJAR -c $NAFCFG >>mta-boot.log 2>&1 &
	;;

    restart)
	$0 stop
	$0 start
	;;


    # USAGE: mta.sh cmd-name [-nocheck] [cmd-args]
    # Eg. to shut down Mailismus:
	#	mta.sh stop
	# Or to send a command to another machine:
	#	bin/mta.sh stop -remote hostname:port
	#	bin/mta.sh dspshow -remote hostname:port mta-deliver
	# where port is the NAFMAN listener (12001 by default).
    *)
	echo "Issuing command=$cmd to Mailismus MTA ..."
	"$JVM" -jar lib/$MTAJAR -c $NAFCFG -cmd $*
	;;
esac

The Mailismus mailserver, featuring SMTP, POP3 and IMAP (with TLS).

Home page: http://www.greyware.co.uk/mailismus
Admin Guide: docs/guide/index.htm in this repo
Source: https://github.com/greysoft/mailismus

To build: mvn clean install

This gives you an executable Jar in server/target (don't use the one that is prefixed as 'original-' as that is an interim build artifact) but you will need to specify a config file to run it.

This build also creates a distribution package (in two formats):
- pkg/target/mailismus-VERSION.zip
- pkg/target/mailismus-VERSION.tar.gz

If you unpack the distribution package, you will find executables, sample config files and documentation.
You can run Mailismus as follows from the root of the unpacked distro.
	bin/mta.sh start
This makes use of the default config files in conf/ which enable the MTA components of the Mailismus server, but not the IMAP or POP servers.
The default config files also require root privilege to run, as they specify the default SMTP port of 25.
You will need to customise the config files for your needs, but this script is inteded to be suitable for use in the init.d boot system of a Unix server.

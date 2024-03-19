/*
 * Copyright 2011-2024 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus;

import com.grey.logging.Logger.LEVEL;
import com.grey.base.config.SysProps;
import com.grey.base.config.XmlConfig;
import com.grey.base.utils.FileOps;
import com.grey.base.utils.DynLoader;
import com.grey.base.collections.HashedSet;
import com.grey.naf.ApplicationContextNAF;
import com.grey.naf.NAFConfig;
import com.grey.mailismus.errors.MailismusConfigException;
import com.grey.mailismus.errors.MailismusStorageException;

public class DBHandle
{
	private static final String DBTYPE_HSQL = "hsql";
	private static final String DBTYPE_H2 = "h2";
	private static final String DBTYPE_DERBY = "derby";
	private static final String DBTYPE_PGSQL = "pgsql";
	private static final String DBTYPE_MSSQL = "mssql";

	private static final String DRVCLASS_HSQL = "org.hsqldb.jdbcDriver";
	private static final String DRVCLASS_H2 = "org.h2.Driver";
	private static final String DRVCLASS_DERBY = "org.apache.derby.jdbc.EmbeddedDriver";
	private static final String DRVCLASS_PGSQL = "org.postgresql.Driver";
	private static final String DRVCLASS_MSSQL = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
	private static final String DRVCLASS_MYSQL = "com.mysql.jdbc.Driver";  // for Connector/J (whatever that means)
	private static final String DFLT_DRVCLASS = DRVCLASS_H2;

	private static final String SYSPROP_AUTOCOMMIT = "greynaf.db.autocommit";
	private static final String SYSPROP_DBDRIVER = "greynaf.db.driver";

	private static final String TOKEN_CONNSTR_PATH = "_FILEPATH_";
	private static final String TOKEN_CONNSTR_NAME = "_DBNAME_";
	private static final String TOKEN_SQLDLM = "_END_";

	private static final String CONNURL_HSQL = "jdbc:hsqldb:file:"+TOKEN_CONNSTR_PATH+";shutdown=true;hsqldb.default_table_type=cached";
	private static final String CONNURL_H2 = "jdbc:h2:file:"+TOKEN_CONNSTR_PATH+";MVCC=TRUE";
	private static final String CONNURL_DERBY = "jdbc:derby:"+TOKEN_CONNSTR_PATH+";create=true";

	private static final boolean showScriptErrors = SysProps.get("greynaf.db.showscripterrors", false);

	public static class Type
	{
		public final java.sql.Driver drvinst;
		public final String connurl;
		public final boolean hasStoredProcs;
		public final String sqlconcat;
		final String idtag;

		public Type(XmlConfig cfg, NAFConfig nafcfg, com.grey.logging.Logger log) throws java.io.IOException
		{
			String drvclass = SysProps.get(SYSPROP_DBDRIVER, DFLT_DRVCLASS);
			String urlpath = (nafcfg == null ? SysProps.TMPDIR+"/mta" : nafcfg.getPathVar());
			urlpath += "/dbdata/"+TOKEN_CONNSTR_NAME;  //generic URL that works for all embedded databases
			String template_url = null;

			if (cfg != null) {
				drvclass = cfg.getValue("drvclass", true, drvclass);
				urlpath = nafcfg.getPath(cfg, "urlpath", null, true, urlpath, null);
				// <connurl>jdbc:h2:file:_FILEPATH_;MVCC=TRUE</connurl>
				template_url = cfg.getValue("connurl", false, null);
			}
			drvclass = drvclass.intern();

			// create directory which we think this points at
			int pos = urlpath.lastIndexOf(SysProps.get("file.separator"));
			if (pos == -1) pos = urlpath.lastIndexOf('/');  //try canonical directory slash
			if (pos != -1) {
				String dirpath = urlpath.substring(0, pos);
				try {
					FileOps.ensureDirExists(dirpath);
				} catch (java.io.IOException ex) {
					// tolerate failure in case we got wrong end of stick and this is not a directory path
					if (log != null) log.info("DBHandle failed to create directory for URL="+urlpath+" - "+com.grey.base.ExceptionUtils.summary(ex, false));
				}
			}
			boolean storedprocs = true;
			String opconcat = "+";  //HSQL supports both versions, and MSSQL only supports this

			if (drvclass == DRVCLASS_HSQL) {
				// See http://hsqldb.org/doc/2.0/guide/dbproperties-chapt.html
				// Appending ";hsqldb.applog=2" may yield more log info
				// Appending ";hsqldb.log_data=false" may enable faster updates, if database recovery not required after a crash
				idtag = DBTYPE_HSQL;
				storedprocs = false;
				if (template_url == null) template_url = CONNURL_HSQL;
			} else if (drvclass == DRVCLASS_H2) {
				// IGNORECASE=TRUE in URL creates tables with case-insensitive columns
				// TRACE_LEVEL_FILE=3 sets maximum trace level (trace file is in same directory as database file)
				// MODE=HSQLDB enables some HSQLDB emulation. Can also set MODE=Derby|MSSQLServer|PostgreSQL
				// MVCC=TRUE enables MVCC - still experimental
				idtag = DBTYPE_H2;
				storedprocs = false;
				opconcat = "||";  // "+" fails
				if (template_url == null) template_url = CONNURL_H2;
			} else if (drvclass == DRVCLASS_DERBY) {
				// URL proprties: http://db.apache.org/derby/manuals/develop/develop15.html
				// System Proprties: http://db.apache.org/derby/docs/10.1/tuning/ctunproper22250.html
				// Since our conn URL specifies absolute path, we don't require SysProp derby.system.home=%DIRVAR%/dbdata
				idtag = DBTYPE_DERBY;
				storedprocs = false;
				opconcat = "||";  // "+" fails
				if (template_url == null) template_url = CONNURL_DERBY;
			} else if (drvclass == DRVCLASS_PGSQL) {
				idtag = DBTYPE_PGSQL;
				opconcat = "||";  // "+" fails
			} else if (drvclass == DRVCLASS_MSSQL) {
				idtag = DBTYPE_MSSQL;
			} else if (drvclass == DRVCLASS_MYSQL) {
				idtag = null;
				opconcat = "||";  //only works in ANSI mode - this is not enabled by default, so would have to use concat()
			} else {
				idtag = null;
			}
			sqlconcat = opconcat;
			hasStoredProcs = storedprocs;

			if (template_url != null) {
				// configured URL was only the pathname part, so embed it in the final URL
				urlpath = template_url.replace(TOKEN_CONNSTR_PATH, urlpath);
			}
			connurl = urlpath;

			Class<?> clss = null;
			try {
				clss = DynLoader.loadClass(drvclass);
				drvinst = (java.sql.Driver)clss.getDeclaredConstructor().newInstance();
			} catch (Exception ex) {
				throw new MailismusConfigException("Failed to create DB-Driver="+drvclass+"/"+clss, ex);
			}
			if (log != null) {
				log.info("Loaded DB driver="+drvinst.getClass().getName()+" - compliant="+drvinst.jdbcCompliant());
			} else {
				System.out.println("Loaded DB driver="+drvinst.getClass().getName());
			}
		}
	}

	public final String name;
	public final Type dbtype;

	private final NAFConfig nafcfg;
	private final String connurl_base;
	private final boolean autocmt;
	private final com.grey.logging.Logger log;

	private java.sql.Connection maincnx;
	private boolean autocmt_actual;

	public final HashedSet<java.sql.Connection> liveconns;

	public DBHandle(String name, Type dbtype, ApplicationContextNAF appctx, XmlConfig cfg, com.grey.logging.Logger log)
	{
		if (dbtype == null) throw new MailismusConfigException("Cannot open DB="+name+" - database-type not defined");
		this.name = name;
		this.dbtype = dbtype;
		this.log = log;
		nafcfg = appctx.getNafConfig();
		connurl_base = dbtype.connurl.replace(TOKEN_CONNSTR_NAME, name);
		liveconns = appctx.getNamedItem(getClass().getName()+"-liveconns", () -> new HashedSet<>());

		boolean acmt = SysProps.get(SYSPROP_AUTOCOMMIT,false);
		if (cfg != null) acmt = cfg.getBool("db_autocommit", acmt);
		autocmt = acmt;
	}

	public void connect(boolean readonly, boolean verbose) throws java.sql.SQLException
	{
		close();
		maincnx = connect(readonly);
		autocmt_actual = maincnx.getAutoCommit(); //just in case the driver hasn't obeyed our setting

		if (verbose) {
			java.sql.DatabaseMetaData mtdata = maincnx.getMetaData();

			// Log some environmental info
			// Holdability: HOLD_CURSORS_OVER_COMMIT=1, CLOSE_CURSORS_AT_COMMIT=2
			// Transaction Isolation: READ_UNCOMMITTED=1, READ_COMMITTED=2, REPEATABLE_READ=4, SERIALIZABLE=8
			log.trace("SQLDB: Driver="+mtdata.getDriverName()+" - Version = "+mtdata.getDriverVersion()
					+" - JDBC="+mtdata.getJDBCMajorVersion()+"."+mtdata.getJDBCMinorVersion()
					+SysProps.EOL+"\tDatabase="+mtdata.getDatabaseProductName()
					+" - Version = "+mtdata.getDatabaseProductVersion()
					+SysProps.EOL+"\tUser="+mtdata.getUserName()
					+" - "+mtdata.getURL()
					+", Catalog="+maincnx.getCatalog()
					+SysProps.EOL
					+"\tAutoCommit="+autocmt_actual
					+", Batches="+mtdata.supportsBatchUpdates()
					+", Transactions="+mtdata.supportsTransactions()
					+", Holdability="+maincnx.getHoldability()
					+", TransactionIsolation="+maincnx.getTransactionIsolation()+"/"+mtdata.getDefaultTransactionIsolation()
					+SysProps.EOL+"\tMaxConns="+mtdata.getMaxConnections()
					+", MaxOpenStmts="+mtdata.getMaxStatements()+", MaxStmt="+mtdata.getMaxStatementLength()
					+" (SelectTables="+mtdata.getMaxTablesInSelect()+", SelectCols="+mtdata.getMaxColumnsInSelect()+")");
		}
	}

	public java.sql.Connection altConnection(boolean readonly) throws java.sql.SQLException
	{
		return connect(readonly);
	}

	public void close()
	{
		if (maincnx == null) return;
		if (log.isActive(LEVEL.TRC)) log.trace("SQLDB="+name+": Closing connection - "+maincnx.getClass().getName());

		try {
			if (DBTYPE_DERBY.equals(dbtype.idtag)) commit(true);  //else it always complains a transaction is open, even in read-only mode
			close(maincnx);
		} catch (Exception ex) {
			log.log(LEVEL.TRC, ex, false, "SQLDB="+name+": close failed");
		}
		maincnx = null;
	}

	// only call this to return connections which were obtained from this class
	public void close(java.sql.Connection cnx) throws java.sql.SQLException
	{
		synchronized (liveconns) {
			cnx.close();
			liveconns.remove(cnx);
		}
	}

	public void commit(boolean txcommit) throws java.sql.SQLException
	{
		commit(maincnx, txcommit, autocmt_actual);
	}

	public void commit(java.sql.Connection cnx, boolean txcommit) throws java.sql.SQLException
	{
		commit(cnx, txcommit, cnx.getAutoCommit());
	}

	// for callers who don't want to be aware of SQL exceptions
	public void commitNOSQL(boolean txcommit)
	{
		try {
			commit(maincnx, txcommit, autocmt_actual);
		} catch (Exception ex) {
			throw new MailismusStorageException("Failed on DB "+(txcommit ? "Commit" : "Rollback"), ex);
		}
	}

	private void commit(java.sql.Connection cnx, boolean txcommit, boolean acmt) throws java.sql.SQLException
	{
		if (acmt) return;

		if (txcommit)
		{
			cnx.commit();
		}
		else
		{
			cnx.rollback();
		}
	}

	public java.sql.PreparedStatement prepareStatement(String sql) throws java.sql.SQLException
	{
		return maincnx.prepareStatement(sql);
	}

	public java.sql.Statement createStatement() throws java.sql.SQLException
	{
		return maincnx.createStatement();
	}

	public java.sql.CallableStatement prepareCall(String cmd) throws java.sql.SQLException
	{
		cmd = "{call "+cmd+"}";
		return maincnx.prepareCall(cmd);
	}

	// Execute the script one command at a time.
	// We continue past any errors as it may just be a case of failing to drop a non-existent object or create an already-existing one. Only
	// the caller can properly evaluate whether any errors were serious.
	public Exception executeScript(CharSequence sqltxt, java.sql.Statement stmt, String[] subold, String[] subnew) throws java.sql.SQLException
	{
		boolean create_stmt = (stmt == null);
		if (stmt == null) stmt = maincnx.createStatement();
		String txt = sqltxt.toString();

		if (subold != null) {
			for (int idx = 0; idx != subold.length; idx++) {
				txt = txt.replace(subold[idx], subnew[idx]);
			}
		}
		String[] sqlcmds = txt.split(TOKEN_SQLDLM);
		Exception ex_first = null;

		try {
			for (int idx = 0; idx != sqlcmds.length; idx++)
			{
				String cmd = sqlcmds[idx].trim();
				if (cmd.length() == 0) continue;
				boolean txcommit = true;

				try {
					stmt.executeUpdate(cmd);
				} catch (Exception ex) {
					if (ex_first == null) ex_first = ex;
					if (showScriptErrors) {
						String msg = "SQLDB="+name+": script cmd="+idx+" failed - "+cmd+"\n"+com.grey.base.ExceptionUtils.summary(ex, false);
						log.info(msg);
					}
					txcommit = false;
				}
				commit(txcommit);
			}
		} finally {
			try {
				if (create_stmt) stmt.close();
			} catch (Exception ex) {
				log.log(LEVEL.TRC, ex, false, "SQLDB="+name+": failed to close script statement");
			}
		}
		return ex_first;
	}

	public Exception executeScript(CharSequence tag, Class<?> clss, XmlConfig cfg, boolean mdty, java.sql.Statement stmt,
			String[] subold, String[] subnew)
		throws java.sql.SQLException, java.io.IOException
	{
		java.net.URL script = null;
		if (cfg != null) script = nafcfg.getURL(cfg, "sqlscript_"+tag, null, false, null, clss);
		if (script == null && dbtype != null) script = DynLoader.getResource(tag+"-"+dbtype.idtag+".sql", true, clss);
		if (script == null) script = DynLoader.getResource(tag+".sql", true, clss);
		if (script == null && !mdty) return null;  // if it is mandatory, just allow the following lines to throw
		log.trace("SQLDB="+name+": Executing script="+script);
		String sql = FileOps.readAsText(script, null);
		return executeScript(sql, stmt, subold, subnew);
	}

	// NB: java.sql.DriverManager.getConnection(connurl) doesn't work, if driver class was not loaded by system classloader
	private java.sql.Connection connect(boolean readonly) throws java.sql.SQLException
	{
		String url = connurl_base;

		if (DBTYPE_HSQL.equals(dbtype.idtag)) {
			if (readonly) url = url+";readonly=true";
		} else if (DBTYPE_H2.equals(dbtype.idtag)) {
			if (readonly) url = url+";ACCESS_MODE_DATA=r";
		}
		java.sql.Connection cnx = dbtype.drvinst.connect(url, new java.util.Properties());  ///Postgresql barfs if Properties is null
		synchronized (liveconns) {
			liveconns.add(cnx);
		}
		cnx.setAutoCommit(autocmt);

		StringBuilder logmsg = new StringBuilder(256);
		logmsg.append("SQLDB=").append(name).append(": Established connection "+liveconns.size()).append(" - ").append(url);
		if (log.isActive(LEVEL.TRC)) log.trace(logmsg);
		return cnx;
	}

	public static String quoteString(String str)
	{
		return "'" + str.replace("'", "''") + "'";
	}
}

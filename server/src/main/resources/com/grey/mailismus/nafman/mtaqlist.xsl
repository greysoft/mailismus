<!--
  Copyright 2013-2018 Yusef Badri - All rights reserved.
  Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
-->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
<xsl:output method="xml" omit-xml-declaration="yes" indent="no"/>

<xsl:param name="d"/>
<xsl:param name="st"/>
<xsl:param name="v"/>

<xsl:template match="/">
<xsl:text disable-output-escaping='yes'>&lt;!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01//EN" "http://www.w3.org/TR/html4/strict.dtd"&gt;</xsl:text>
<html>
	<head>
		<title>NAFMAN-Web</title>
		<link rel="stylesheet" type="text/css" href="nafman.css"/>
		<meta http-equiv="Content-Type" content="text/html;charset=UTF-8"/>
	</head>
	<body>
		<div class="pagetitle">
			MTA Queue
		</div>
		<p>
			<xsl:element name="a">
				<xsl:attribute name="class">buttonlink</xsl:attribute>
				<xsl:attribute name="href">/</xsl:attribute>
				<span class="infobutton">Home</span>
			</xsl:element>
			<br/><br/>
			<xsl:element name="a">
				<xsl:attribute name="class">buttonlink</xsl:attribute>
				<xsl:attribute name="href">QLIST?d=<xsl:value-of select="$d"/>%26st=<xsl:value-of select="$st"/>%26v=<xsl:value-of select="$v"/></xsl:attribute>
				<span class="infobutton">Refresh</span>
			</xsl:element>
		</p>
		<p>
			Total Messages: <xsl:value-of select="//summary/@total"/><br/>
		</p>
		<table border="1" cellpadding="10">
			<xsl:apply-templates select="//qrows/qrow[1]" mode="header"/>
			<xsl:apply-templates select="//qrows/qrow" mode="data"/>
		</table>
		<xsl:if test="//summary/@total!='0'">
			<p>
				<xsl:element name="a">
					<xsl:attribute name="class">buttonlink</xsl:attribute>
					<xsl:attribute name="href">QLIST?st=mtaqcsv</xsl:attribute>
					<xsl:attribute name="title">Display in tabular CSV format, suitable for spreadsheet import</xsl:attribute>
					<span class="infobutton">CSV-Format</span>
				</xsl:element>
		</p>
		</xsl:if>
	</body>
</html>
</xsl:template>

<xsl:template match="qrow" mode="header">
	<tr>
		<xsl:for-each select="attribute::*">
			<th><xsl:value-of select="local-name(.)"/></th>
		</xsl:for-each>
	</tr>
</xsl:template>

<xsl:template match="qrow" mode="data">
	<tr>
		<xsl:for-each select="attribute::*">
			<td><xsl:value-of select="."/></td>
		</xsl:for-each>
	</tr>
</xsl:template>

</xsl:stylesheet>

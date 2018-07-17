<!--
  Copyright 2013-2018 Yusef Badri - All rights reserved.
  Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
-->
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
<xsl:output method="text"/>

<xsl:template match="/">
	<xsl:apply-templates select="//rows/row[1]" mode="header"/>
	<xsl:text>&#10;</xsl:text>
	<xsl:apply-templates select="//rows/row" mode="data"/>
</xsl:template>

<xsl:template match="row" mode="header">
	<xsl:for-each select="attribute::*"><xsl:value-of select="local-name(.)"/>,</xsl:for-each>
</xsl:template>

<xsl:template match="row" mode="data">
	<xsl:for-each select="attribute::*"><xsl:value-of select="."/>,</xsl:for-each>
	<xsl:text>&#10;</xsl:text>
</xsl:template>

</xsl:stylesheet>

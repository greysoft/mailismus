/*
 * Copyright 2013 Yusef Badri - All rights reserved.
 * Mailismus is distributed under the terms of the GNU Affero General Public License, Version 3 (AGPLv3).
 */
package com.grey.mailismus.ms.maildir;

import com.grey.base.utils.StringOps;

/*
 * See RFC-2045 & 2046
 * There are 5 discrete content-types: Text, Image, Audio, Video, Application
 * Multipart and Message are the 2 content-types which represent composite media types
 * - multipart subtypes: mixed, alternative, digest, parallel
 * - message subtypes: rfc822, partial, external-body
 * See RFC-2046 4.1 for the Text media type
 * 
 * 2046:5.1 says missing Content-Type defaults to text/plain
 * 5.1.1 clarifies: If no Content-Type field is present it is assumed to be "message/rfc822" in a "multipart/digest" and "text/plain" otherwise.
 *
 * 2046:5.1.2 says we must recognise parent's boundaries as well when parsing sub-part, in case terminating boundary got removed
 */
public class MimePart
{
	public static final String HDR_CTYPE = "Content-Type";
	public static final String HDR_ENCODING = "Content-Transfer-Encoding";
	public static final String HDR_DISPOSITION = "Content-Disposition"; //defined in RFC-2183
	public static final String HDR_CID = "Content-ID";
	public static final String HDR_CDESC = "Content-Description";
	public static final String HDR_LANG = "Content-Language";
	public static final String HDR_SUBJECT = "Subject";

	public static final String ATTR_CHARSET = "charset";
	public static final String ATTR_BNDRY = "boundary";
	public static final String ATTR_FILENAME = "filename";
	public static final String ATTR_NAME = "name";
	public static final String ATTR_SIZE = "size";

	private static final String TAG_CHARSET = ATTR_CHARSET+"=";
	private static final String TAG_BNDRY = ATTR_BNDRY+"=";
	private static final String TAG_FILENAME = ATTR_FILENAME+"=";
	private static final String TAG_NAME = ATTR_NAME+"=";
	private static final String TAG_SIZE = ATTR_SIZE+"=";

	public final boolean isMessage;
	public String ctype;
	public String subtype;
	public String charset; //applicable to any subtype of "text"
	public String bndry; //required for any subtype of "multipart"
	public String name;
	public String encoding;
	public String contid;
	public String contdesc;
	public String language;
	public String disposition_type;
	public String disposition_filename;
	public String disposition_size;
	public int linecnt; //excludes header
	public int bodysiz; //excludes header
	int totalsiz; //includes header
	long file_off;  //absolute offset of entire bodypart (header and content) within message-file
	private java.util.ArrayList<MimePart> subparts;
	private MimePart msgnode;  //null unless this object represents a nested-message bodypart

	private java.util.ArrayList<MimePart> children() {return msgnode == null ? subparts : msgnode.subparts;}
	public int childCount() {java.util.ArrayList<MimePart> c = children(); return (c == null ? 0 : c.size());}
	public MimePart getChild(int idx) {return children().get(idx);}
	public boolean isNestedMessage() {return "message".equalsIgnoreCase(ctype) && "rfc822".equalsIgnoreCase(subtype);}
	public MimePart getMessage() {return isMessage ? this : msgnode;}
	public void setMessage(MimePart p) {msgnode = p;}

	public MimePart(boolean is_message)
	{
		isMessage = is_message;
	}

	public void parseHeaders(com.grey.base.collections.HashedMap<String,String> hdrmap, String parent_ctype, String parent_subtype)
	{
		parseContentType(hdrmap.get(HDR_CTYPE), parent_ctype, parent_subtype);
		parseDisposition(hdrmap.get(HDR_DISPOSITION));
		encoding = hdrmap.get(HDR_ENCODING);
		contid = hdrmap.get(HDR_CID);
		contdesc = hdrmap.get(HDR_CDESC);
		language = hdrmap.get(HDR_LANG);
		if (encoding == null) encoding = "7bit"; //RFC-2045 section 6.1 specifies this default
	}

	public MimePart addChildPart()
	{
		if (subparts == null) subparts = new java.util.ArrayList<MimePart>();
		MimePart child = new MimePart(false);
		subparts.add(child);
		return child;
	}

	private void parseContentType(String hdrval, String parent_ctype, String parent_subtype)
	{
		if (hdrval == null) {
			// RFC-2046 section 5.1 says missing Content-Type defaults to text/plain
			// 5.1.1 clarifies: If no Content-Type field is present it is assumed to be "message/rfc822" in a "multipart/digest"
			// and "text/plain" otherwise.
			if ("multipart".equalsIgnoreCase(parent_ctype) && "digest".equalsIgnoreCase(parent_subtype)) {
				ctype = "message";
				subtype = "rfc822";
			} else {
				ctype = "text";
				subtype = "plain";
			}
			charset = "US-ASCII";
			return;
		}
		int pos_slash = hdrval.indexOf('/');
		int pos_semicolon = hdrval.indexOf(';');
		int lmt = (pos_slash == -1 ? pos_semicolon : pos_slash);
		ctype = (lmt == -1 ? hdrval : hdrval.substring(0, lmt));
		if (pos_slash != -1) subtype = hdrval.substring(pos_slash+1, pos_semicolon == -1 ? hdrval.length() : pos_semicolon);
		charset = parseAttribute(hdrval, TAG_CHARSET); //RFC-2046 section 4.1.2
		if (charset == null && "text".equalsIgnoreCase(ctype) && "plain".equalsIgnoreCase(subtype)) charset = "US-ASCII";
		bndry = parseAttribute(hdrval, TAG_BNDRY);
		name = parseAttribute(hdrval, TAG_NAME);
	}

	private void parseDisposition(String hdrval)
	{
		if (hdrval == null) return;
		int lmt = hdrval.indexOf(';');
		int pos = -1;
		if (lmt == -1) {
			disposition_type = hdrval;
		} else {
			pos = hdrval.indexOf('=');
			if (pos != -1 && pos < lmt) return; //disposition-type is compulsory first item, so this looks corrupt
			disposition_type = hdrval.substring(0, lmt);
		}
		if (pos == -1) return; //no further attributes
		disposition_filename = parseAttribute(hdrval, TAG_FILENAME);
		disposition_size = parseAttribute(hdrval, TAG_SIZE);
	}

	private String parseAttribute(String hdrval, String attrnam)
	{
		int pos = StringOps.indexOfNoCase(hdrval, attrnam);
		if (pos == -1) return null;
		pos += attrnam.length();
		int lmt = hdrval.indexOf(';', pos);
		if (lmt == -1) lmt = hdrval.length();
		while (pos != lmt && hdrval.charAt(lmt-1) <= ' ') lmt--;
		while (pos != lmt && hdrval.charAt(pos) <= ' ') pos++;
		if (pos != lmt && hdrval.charAt(pos) == '"') {
			pos++;
			if (pos != lmt && hdrval.charAt(lmt-1) == '"') lmt--;
		}
		return hdrval.substring(pos, lmt);
	}

	@Override
	public String toString()
	{
		return toString(new StringBuilder(256), true).toString();
	}

	private StringBuilder toString(StringBuilder sb, boolean with_children)
	{
		if (isMessage) sb.append("Message-");
		sb.append("MimePart@").append(file_off).append(':').append(totalsiz).append("/body=").append(bodysiz).append(':').append(linecnt);
		sb.append(" Content-Type=").append(ctype).append('/').append(subtype);
		sb.append("/charset=").append(charset).append("/bndry=").append(bndry!=null).append("/name=").append(name);
		if (disposition_type != null) {
			sb.append("; Disposition=").append(disposition_type).append("/filename=").append(disposition_filename);
		}
		if (encoding != null) sb.append("; Transfer-Encoding=").append(encoding);
		if (contid != null) sb.append("; ID=").append(contid);
		if (contdesc != null) sb.append("; Description=").append(contdesc);
		if (language != null) sb.append("; Language=").append(language);
		if (msgnode != null) {
			sb.append(" [");
			msgnode.toString(sb, false);
			sb.append(']');
		}
		java.util.ArrayList<MimePart> c = children();
		if (with_children && c != null && c.size() != 0) sb.append(" - SubParts="+c.size()+" "+c);
		return sb;
	}
}
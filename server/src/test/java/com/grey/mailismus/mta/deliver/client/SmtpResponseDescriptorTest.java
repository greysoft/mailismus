package com.grey.mailismus.mta.deliver.client;

import java.nio.charset.StandardCharsets;

import com.grey.base.utils.ByteArrayRef;

import static org.junit.Assert.*;

import org.junit.Test;

public class SmtpResponseDescriptorTest {
	@Test
	public void testNoEnhanced() {
		String str = "250 All OK";
		ByteArrayRef rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		SmtpResponseDescriptor reply = SmtpResponseDescriptor.parse(rspdata, false);
		assertEquals(250, reply.smtpStatus());
		assertEquals("All OK", reply.message());
		assertNull(reply.enhancedStatus());

		str = "250 2.1.0 OK";
		rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		reply = SmtpResponseDescriptor.parse(rspdata, false);
		assertEquals(250, reply.smtpStatus());
		assertEquals("2.1.0 OK", reply.message());
		assertNull(reply.enhancedStatus());

		str = "250 2.1.0";
		rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		reply = SmtpResponseDescriptor.parse(rspdata, false);
		assertEquals(250, reply.smtpStatus());
		assertEquals("2.1.0", reply.message());
		assertNull(reply.enhancedStatus());

		str = "500";
		rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		reply = SmtpResponseDescriptor.parse(rspdata, false);
		assertEquals(500, reply.smtpStatus());
		assertTrue(reply.toString(), reply.message().isEmpty());
		assertNull(reply.enhancedStatus());
	}

	@Test
	public void testWithEnhanced() {
		String str = "250 All OK";
		ByteArrayRef rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		SmtpResponseDescriptor reply = SmtpResponseDescriptor.parse(rspdata, true);
		assertEquals(250, reply.smtpStatus());
		assertEquals("OK", reply.message());
		assertEquals("All", reply.enhancedStatus());

		str = "250 2.1.0 OK";
		rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		reply = SmtpResponseDescriptor.parse(rspdata, true);
		assertEquals(250, reply.smtpStatus());
		assertEquals("OK", reply.message());
		assertEquals("2.1.0", reply.enhancedStatus());

		str = "250 2.1.0";
		rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		reply = SmtpResponseDescriptor.parse(rspdata, true);
		assertEquals(250, reply.smtpStatus());
		assertTrue(reply.toString(), reply.message().isEmpty());
		assertEquals("2.1.0", reply.enhancedStatus());

		str = "500";
		rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		reply = SmtpResponseDescriptor.parse(rspdata, true);
		assertEquals(500, reply.smtpStatus());
		assertTrue(reply.toString(), reply.message().isEmpty());
		assertNull(reply.enhancedStatus());
	}

	@Test
	public void testWithEOL() {
		String str = "250 All OK\r\n";
		ByteArrayRef rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		SmtpResponseDescriptor reply = SmtpResponseDescriptor.parse(rspdata, true);
		assertEquals(250, reply.smtpStatus());
		assertEquals("OK", reply.message());
		assertEquals("All", reply.enhancedStatus());
		reply = SmtpResponseDescriptor.parse(rspdata, false);
		assertEquals(250, reply.smtpStatus());
		assertEquals("All OK", reply.message());
		assertNull(reply.enhancedStatus());

		str = "250 All OK\n";
		rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		reply = SmtpResponseDescriptor.parse(rspdata, true);
		assertEquals(250, reply.smtpStatus());
		assertEquals("OK", reply.message());
		assertEquals("All", reply.enhancedStatus());
		reply = SmtpResponseDescriptor.parse(rspdata, false);
		assertEquals(250, reply.smtpStatus());
		assertEquals("All OK", reply.message());
		assertNull(reply.enhancedStatus());

		str = "250 All OK"+"".repeat(3);
		rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		reply = SmtpResponseDescriptor.parse(rspdata, true);
		assertEquals(250, reply.smtpStatus());
		assertEquals("OK", reply.message());
		assertEquals("All", reply.enhancedStatus());
		reply = SmtpResponseDescriptor.parse(rspdata, false);
		assertEquals(250, reply.smtpStatus());
		assertEquals("All OK", reply.message());
		assertNull(reply.enhancedStatus());
	}

	@Test
	public void testInvalid() {
		String str = "";
		ByteArrayRef rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		SmtpResponseDescriptor reply = SmtpResponseDescriptor.parse(rspdata, true);
		assertNull(reply);
		reply = SmtpResponseDescriptor.parse(rspdata, false);
		assertNull(reply);

		str = " ".repeat(5);
		rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		reply = SmtpResponseDescriptor.parse(rspdata, true);
		assertNull(reply);
		reply = SmtpResponseDescriptor.parse(rspdata, false);
		assertNull(reply);

		str = "25";
		rspdata = new ByteArrayRef(str.getBytes(StandardCharsets.UTF_8));
		reply = SmtpResponseDescriptor.parse(rspdata, true);
		assertNull(reply);
		reply = SmtpResponseDescriptor.parse(rspdata, false);
		assertNull(reply);
	}
}

package com.grey.mailismus.mta.deliver;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;

import com.grey.mailismus.mta.deliver.client.SmtpRelay;
import com.grey.mailismus.mta.testsupport.MockSmtpServer;
import com.grey.naf.BufferGenerator;
import com.grey.naf.reactor.Dispatcher;
import com.grey.naf.reactor.config.DispatcherConfig;
import com.grey.base.utils.TSAP;
import com.grey.base.utils.TimeOps;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/*xxxx Need to test: HELO, EHLO, EHLO rejected, SASL, STLS, some/all recips rejected, msg rejected, quit error, connection-loss in mid session
 * Also a test with DNS enabled, but interceptor in place to ensure we connect to mock server anyway - how do we verify the DNS lookups?
 * Interceptor would be in SharedFields if we bring it back
 */
public class ClientTest {
	private MockSmtpServer srvr;

	@Before
	public void setup() throws Exception {
		srvr = new MockSmtpServer(null);
		srvr.start();
	}

	@After
	public void teardown() throws Exception {
		if (srvr != null) {
			srvr.stop();
			Thread.sleep(100);
		}
	}

	//xxx @Test
	public void testSanity() throws Exception {
		Socket sock = srvr.connect();
		OutputStream ostrm = sock.getOutputStream();
		PrintWriter sendStream = new PrintWriter(ostrm, true);
		InputStream istrm = sock.getInputStream();
		InputStreamReader rdr = new InputStreamReader(istrm);
		BufferedReader recvStream = new BufferedReader(rdr);
		sendStream.println("Hello I am the client");
		String s = recvStream.readLine();
		System.out.println("Client received ["+s+"]");
		sock.close(); //xxx srvr.closeSession(sock.getPort());
		Thread.sleep(500);
	}

	@Test
	public void testSuccessfulSend() throws Exception {
		BufferGenerator.BufferConfig bufcfg = new BufferGenerator.BufferConfig(256, true, null, null);
		BufferGenerator bufgen = new BufferGenerator(bufcfg);
		TSAP remote = TSAP.build("127.0.0.1", srvr.getServicePort());

		DispatcherConfig def = DispatcherConfig.builder()
				.withName("test-dispatcher1")
				.withSurviveHandlers(false)
				.build();
		Dispatcher dsptch = Dispatcher.create(def);
		
		SharedFields shared = SharedFields.builder()
				.withBufferGenerator(bufgen)
				.build();
		SmtpRelay relay = SmtpRelay.builder()
				.withName("test-relay1")
				.withAddress(remote)
				.build();
		Client clnt = new Client(shared, dsptch);
		
		//xxx clnt.startConnection(null, null, relay);
		dsptch.start();
		Dispatcher.STOPSTATUS stopsts = dsptch.waitStopped(TimeOps.MSECS_PER_SECOND*10L, true);
		System.out.println("xxx client="+clnt+", relay="+relay+", stopsts="+stopsts);
	}
}

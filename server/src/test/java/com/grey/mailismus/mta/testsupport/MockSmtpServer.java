package com.grey.mailismus.mta.testsupport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;

public class MockSmtpServer
{
	private final ServerSocket srvrSocket;
	private volatile boolean shutdown;

	public MockSmtpServer(Map<String,String> expectSend) throws IOException {
		srvrSocket = new ServerSocket(0, 10, InetAddress.getLoopbackAddress());
	}

	public void start() {
		Runnable r = () -> runService();
		Thread t = new Thread(r);
		t.start();
	}

	public void stop() throws IOException {
		shutdown = true;
		srvrSocket.close();
	}

	public int getServicePort() {
		return srvrSocket.getLocalPort();
	}

	public Socket connect() throws IOException {
		return new Socket(InetAddress.getLoopbackAddress(), srvrSocket.getLocalPort());
	}

	private void runService() {
		for (;;) {
			try {
				handleConnection();
			} catch (Throwable ex) {
				if (!shutdown) System.out.println("Mock SMTP server error - "+ex);
				break;
			}
		}
		System.out.println("SMTP server terminating - "+srvrSocket);
	}

	private void handleConnection() throws IOException {
		Socket sock = srvrSocket.accept();
		System.out.println("SMTP session accepted - "+sock);
		SmtpSession session = new SmtpSession(sock);
		Runnable r = () -> session.serveSession();
		Thread t = new Thread(r);
		t.start();
	}


	private static class SmtpSession {
		private final Socket sock;
		private final BufferedReader recvStream;
		private final PrintWriter sendStream;

		public SmtpSession(Socket sock) throws IOException {
			this.sock = sock;
			InputStream istrm = sock.getInputStream();
			InputStreamReader rdr = new InputStreamReader(istrm);
			recvStream = new BufferedReader(rdr);
			OutputStream ostrm = sock.getOutputStream();
			sendStream = new PrintWriter(ostrm, true);
		}

		public void serveSession() {
			try {
				serviceLoop();
			} catch (Throwable ex) {
				System.out.println("SMTP session exiting on error - "+ex);
			}
			System.out.println("SMTP session terminating - "+sock);
		}

		private void serviceLoop() throws IOException {
			String request;
			while ((request = recvStream.readLine()) != null) {
				System.out.println("xxx session received ["+request+"]");
				sendStream.println("Hello I am the server");
			}
		}
	}
}
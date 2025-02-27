/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.tcp;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.BeforeClass;
import org.junit.Test;
import reactor.core.publisher.Mono;

import javax.net.ssl.SSLException;
import java.security.cert.CertificateException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;
import static reactor.netty.Metrics.REMOTE_ADDRESS;
import static reactor.netty.Metrics.STATUS;
import static reactor.netty.Metrics.URI;

/**
 * @author Violeta Georgieva
 */
public class TcpSecureMetricsTests extends TcpMetricsTests {
	private static SelfSignedCertificate ssc;

	@BeforeClass
	public static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	@Override
	protected TcpServer customizeServerOptions(TcpServer tcpServer) {
		try {
			SslContext ctx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
			return tcpServer.secure(ssl -> ssl.sslContext(ctx));
		}
		catch (SSLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected TcpClient customizeClientOptions(TcpClient tcpClient) {
		try {
			SslContext ctx = SslContextBuilder.forClient()
			                                  .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
			return tcpClient.secure(ssl -> ssl.sslContext(ctx));
		}
		catch (SSLException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	protected void checkTlsTimer(String name, String[] tags, long expectedCount, double expectedTime) {
		checkTimer(name, tags, expectedCount, expectedTime);
	}

	@Test
	public void testFailedTlsHandshake() throws Exception {
		disposableServer = customizeServerOptions(tcpServer).bindNow();

		connection = customizeClientOptions(tcpClient)
		                     .noSSL()
		                     .connectNow();

		connection.outbound()
		          .sendString(Mono.just("hello"))
		          .neverComplete()
		          .subscribe();

		CountDownLatch latch = new CountDownLatch(1);
		connection.inbound()
		          .receive()
		          .asString()
		          .subscribe(null, null, latch::countDown);

		assertTrue(latch.await(30, TimeUnit.SECONDS));

		checkExpectationsNegative();
	}

	private void checkExpectationsNegative() {
		String address = disposableServer.address().getHostString();
		String[] timerTags = new String[] {REMOTE_ADDRESS, address, STATUS, "ERROR"};
		String[] summaryTags = new String[] {REMOTE_ADDRESS, address, URI, "tcp"};

		checkTlsTimer(SERVER_TLS_HANDSHAKE_TIME, timerTags, 1, 0.0001);
		checkDistributionSummary(SERVER_DATA_SENT, summaryTags, 0, 0);
		checkDistributionSummary(SERVER_DATA_RECEIVED, summaryTags, 0, 0);
		checkCounter(SERVER_ERRORS, summaryTags, 2);

		timerTags = new String[] {REMOTE_ADDRESS, address, STATUS, "SUCCESS"};

		checkTimer(CLIENT_CONNECT_TIME, timerTags, 1, 0.0001);
		checkDistributionSummary(CLIENT_DATA_SENT, summaryTags, 1, 5);
		checkDistributionSummary(CLIENT_DATA_RECEIVED, summaryTags, 1, 7);
		checkCounter(CLIENT_ERRORS, summaryTags, 0);
	}
}

package com.se.telecomalarm.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.core.env.Environment;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Not a test of the application: a check of the test set-up itself (the WireMock receiver), so that a failure of
 * the set-up is reported here, by name, and not as a strange failure of some E test.
 * <p>
 * The receiver speaks HTTPS only, with the self-signed local-development certificate. The probe below trusts it the
 * same way the application does, through the SSL bundle {@value #RECEIVER_SSL_BUNDLE}: if the bundle and the
 * receiver's certificate do not match, this test fails on the TLS handshake, by name.
 */
class NotificationReceiverSetupTests extends ApiTestBase {

    private static final String BASE_URL_PROPERTY = "spring.http.serviceclient.notifications.base-url";

    /**
     * The SSL bundle of the main application.yaml that trusts the receiver's certificate.
     */
    private static final String RECEIVER_SSL_BUNDLE = "notification-receiver";

    @Autowired
    private Environment environment;

    @Autowired
    private SslBundles sslBundles;

    @Test
    @DisplayName("Set-up: the notification address points at the WireMock receiver and the default stub answers 200")
    void notificationAddress_pointsAtReceiver_andDefaultStubAnswers200() throws Exception {
        String baseUrl = environment.getRequiredProperty(BASE_URL_PROPERTY);
        assertThat(baseUrl)
                .as("%s: the WireMock HTTPS base URL, without the endpoint path", BASE_URL_PROPERTY)
                .isEqualTo("https://localhost:" + receiver.httpsPort());

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder()
                .sslContext(sslBundles.getBundle(RECEIVER_SSL_BUNDLE).createSslContext())
                .version(HttpClient.Version.HTTP_1_1)
                .build()) {
            response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + NOTIFICATIONS_PATH))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", JSON)
                            .POST(HttpRequest.BodyPublishers.ofString("{\"probe\":true}", StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        assertThat(response.statusCode()).as("answer of the default stub").isEqualTo(200);
        receiver.verify(1, postRequestedFor(urlEqualTo(NOTIFICATIONS_PATH)));
        assertThat(receivedNotifications())
                .as("the NotificationRecorder extension saw the same request")
                .singleElement()
                .satisfies(received -> assertThat(received.body()).isEqualTo("{\"probe\":true}"));
    }
}

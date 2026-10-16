# Local-development TLS certificate of the notification receiver

LOCAL DEVELOPMENT AND TESTS ONLY. A self-signed certificate for `localhost` / `127.0.0.1`, so the notification client
can talk HTTPS to WireMock (tests and docker-compose) with the certificate and the host name really checked.
WireMock's own built-in certificate is issued to "CN=Tom Akehurst" without `localhost`, so the JDK client rejects it.

- `notification-receiver-dev.p12` (this folder): private key + certificate, PKCS12, password `wiremock-dev-only`.
  Used by the WireMock server: `@ConfigureWireMock` in `ApiTestBase`, the `wiremock` service in `docker-compose.yml`.
- `src/main/resources/tls/notification-receiver-dev.crt`: the public certificate only, trusted by the application
  through the SSL bundle `notification-receiver` (`src/main/resources/application.yaml`).

Valid until October 2036. Created with (JDK keytool):

```
keytool -genkeypair -alias notification-receiver -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=localhost, O=Telecom Alarm Notification Service (local development only)" \
  -ext "SAN=dns:localhost,ip:127.0.0.1" -storetype PKCS12 \
  -keystore notification-receiver-dev.p12 -storepass wiremock-dev-only
keytool -exportcert -rfc -alias notification-receiver -keystore notification-receiver-dev.p12 \
  -storepass wiremock-dev-only -file notification-receiver-dev.crt
```

Both files must be replaced together: the application trusts exactly this certificate.

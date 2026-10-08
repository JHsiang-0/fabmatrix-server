package com.example.farm.protocol;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.OK;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PrinterProtocolDetectorTest {

    @Test
    void detectsKlipperFromMoonrakerResponse() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://192.168.1.80:7125/server/info"))
                .andRespond(withSuccess("{\"result\":{\"klippy_version\":\"v0.12\"}}", MediaType.APPLICATION_JSON));

        assertThat(new PrinterProtocolDetector(client).detect("192.168.1.80"))
                .isEqualTo(PrinterProtocolType.KLIPPER);
        server.verify();
    }

    @Test
    void detectsRrfAfterMoonrakerProbeFails() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://192.168.1.81:7125/server/info"))
                .andRespond(withStatus(OK.NOT_FOUND));
        server.expect(requestTo("http://192.168.1.81/rr_connect?password=&sessionKey=yes"))
                .andRespond(withSuccess("{\"err\":1}", MediaType.APPLICATION_JSON));

        assertThat(new PrinterProtocolDetector(client).detect("192.168.1.81"))
                .isEqualTo(PrinterProtocolType.RRF);
        server.verify();
    }

    @Test
    void returnsNullForUnknownHttpDevice() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://192.168.1.82:7125/server/info"))
                .andRespond(withStatus(OK.NOT_FOUND));
        server.expect(requestTo("http://192.168.1.82/rr_connect?password=&sessionKey=yes"))
                .andRespond(withSuccess("<html>not a printer</html>", MediaType.TEXT_HTML));

        assertThat(new PrinterProtocolDetector(client).detect("192.168.1.82")).isNull();
        server.verify();
    }

    @Test
    void successfulRrfProbeReleasesItsOwnSessionKey() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://192.168.1.83:7125/server/info"))
                .andRespond(withStatus(OK.NOT_FOUND));
        server.expect(requestTo("http://192.168.1.83/rr_connect?password=&sessionKey=yes"))
                .andExpect(headerDoesNotExist("X-Session-Key"))
                .andRespond(withSuccess("{\"err\":0,\"sessionKey\":\"probe-session\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://192.168.1.83/rr_disconnect"))
                .andExpect(header("X-Session-Key", "probe-session"))
                .andRespond(withSuccess("{\"err\":0}", MediaType.APPLICATION_JSON));

        assertThat(new PrinterProtocolDetector(client).detect("192.168.1.83")).isEqualTo(PrinterProtocolType.RRF);
        server.verify();
    }

    @Test
    void successfulCompatibilityRrfProbeDoesNotDisconnectSharedIpSession() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://192.168.1.84:7125/server/info"))
                .andRespond(withStatus(OK.NOT_FOUND));
        server.expect(requestTo("http://192.168.1.84/rr_connect?password=&sessionKey=yes"))
                .andRespond(withSuccess("{\"err\":0}", MediaType.APPLICATION_JSON));
        assertThat(new PrinterProtocolDetector(client).detect("192.168.1.84")).isEqualTo(PrinterProtocolType.RRF);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void identifiesProtectedMoonrakerWithAnonymousAuthorizationInfo(int status) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://192.168.1.89:7125/server/info"))
                .andExpect(headerDoesNotExist("X-Api-Key"))
                .andRespond(withStatus(HttpStatus.valueOf(status)));
        server.expect(requestTo("http://192.168.1.89:7125/access/info"))
                .andExpect(headerDoesNotExist("X-Api-Key"))
                .andRespond(withSuccess("""
                        {"result":{"default_source":"moonraker","available_sources":["moonraker","ldap"],
                          "login_required":true,"trusted":false}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(new PrinterProtocolDetector(client).detect("192.168.1.89")).isEqualTo(PrinterProtocolType.KLIPPER);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"result\":{\"trusted\":false}}", "{\"result\":null}",
            "{\"result\":{\"default_source\":\"moonraker\",\"available_sources\":[\"moonraker\"],\"login_required\":\"true\",\"trusted\":false}}",
            "{\"result\":{\"default_source\":\"other\",\"available_sources\":[\"other\"],\"login_required\":true,\"trusted\":false}}",
            "{\"result\":{\"default_source\":\"moonraker\",\"available_sources\":[],\"login_required\":true,\"trusted\":false}}"})
    void authenticationFailureWithoutMoonrakerInfoCannotIdentifyDevice(String body) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://192.168.1.90:7125/server/info"))
                .andRespond(withStatus(OK.UNAUTHORIZED));
        server.expect(requestTo("http://192.168.1.90:7125/access/info"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://192.168.1.90/rr_connect?password=&sessionKey=yes"))
                .andRespond(withStatus(OK.NOT_FOUND));

        assertThat(new PrinterProtocolDetector(client).detect("192.168.1.90")).isNull();
        server.verify();
    }

    @Test
    void failureOfAnonymousMoonrakerInfoFallsBackToRrfProbe() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://192.168.1.91:7125/server/info"))
                .andRespond(withStatus(OK.FORBIDDEN));
        server.expect(requestTo("http://192.168.1.91:7125/access/info"))
                .andRespond(withStatus(OK.FORBIDDEN));
        server.expect(requestTo("http://192.168.1.91/rr_connect?password=&sessionKey=yes"))
                .andRespond(withSuccess("{\"err\":1}", MediaType.APPLICATION_JSON));

        assertThat(new PrinterProtocolDetector(client).detect("192.168.1.91")).isEqualTo(PrinterProtocolType.RRF);
        server.verify();
    }

    @Test
    void unsuccessfulRrfProbeDoesNotDisconnectAnotherSession() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://192.168.1.85:7125/server/info"))
                .andRespond(withStatus(OK.NOT_FOUND));
        server.expect(requestTo("http://192.168.1.85/rr_connect?password=&sessionKey=yes"))
                .andRespond(withSuccess("{\"err\":2,\"sessionKey\":\"other-session\"}", MediaType.APPLICATION_JSON));

        assertThat(new PrinterProtocolDetector(client).detect("192.168.1.85")).isEqualTo(PrinterProtocolType.RRF);
        server.verify();
    }

    @Test
    void rrfCleanupFailureDoesNotDiscardDetectedProtocol() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();
        server.expect(requestTo("http://192.168.1.86:7125/server/info"))
                .andRespond(withStatus(OK.NOT_FOUND));
        server.expect(requestTo("http://192.168.1.86/rr_connect?password=&sessionKey=yes"))
                .andRespond(withSuccess("{\"err\":0,\"sessionKey\":\"probe-session\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://192.168.1.86/rr_disconnect"))
                .andExpect(header("X-Session-Key", "probe-session"))
                .andRespond(withStatus(OK.SERVICE_UNAVAILABLE));

        assertThat(new PrinterProtocolDetector(client).detect("192.168.1.86")).isEqualTo(PrinterProtocolType.RRF);
        server.verify();
    }

    @Test
    void arbitraryErrFieldIsNotEnoughToIdentifyRrf() {
        for (String body : new String[]{"{\"err\":null}", "{\"err\":\"failure\"}", "{\"err\":true}",
                "{\"err\":0.0}", "{\"err\":-1}", "{\"err\":99}"}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            RestClient client = builder.build();
            server.expect(requestTo("http://192.168.1.87:7125/server/info"))
                    .andRespond(withStatus(OK.NOT_FOUND));
            server.expect(requestTo("http://192.168.1.87/rr_connect?password=&sessionKey=yes"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            assertThat(new PrinterProtocolDetector(client).detect("192.168.1.87")).isNull();
            server.verify();
        }
    }

    @Test
    void interruptedProbeDoesNotStartHttpRequestsAndPreservesInterrupt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        PrinterProtocolDetector detector = new PrinterProtocolDetector(builder.build());
        try {
            Thread.currentThread().interrupt();
            assertThat(detector.detect("192.168.1.88")).isNull();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            server.verify();
        } finally {
            Thread.interrupted();
        }
    }
}

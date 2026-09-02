package com.example.farm.protocol;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.OK;
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
}

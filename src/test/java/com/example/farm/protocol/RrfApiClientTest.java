package com.example.farm.protocol;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.hamcrest.Matchers.startsWith;

class RrfApiClientTest {

    private MockRestServiceServer server;
    private RrfApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RrfApiClient(builder.build());
    }

    @Test
    void connectsQueriesStateAndJobThenDisconnects() {
        expectConnect();
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_model")))
                .andExpect(method(GET))
                .andExpect(queryParam("key", "state"))
                .andExpect(header("X-Session-Key", "123"))
                .andRespond(withSuccess("{\"key\":\"state\",\"result\":{\"status\":\"processing\",\"currentTool\":0,\"upTime\":1234}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_model")))
                .andExpect(method(GET))
                .andExpect(queryParam("key", "job"))
                .andExpect(header("X-Session-Key", "123"))
                .andRespond(withSuccess("{\"key\":\"job\",\"result\":{\"file\":{\"fileName\":\"demo.gcode\",\"size\":1000},\"filePosition\":425,\"timesLeft\":{\"file\":12},\"lastFileCancelled\":false,\"lastFileAborted\":false}}",
                        MediaType.APPLICATION_JSON));
        expectTemperatureModels(
                "{\"key\":\"heat\",\"result\":{\"bedHeaters\":[-1],\"heaters\":[{\"current\":55.4,\"active\":60,\"standby\":0,\"state\":\"active\",\"sensor\":0},{\"current\":237.3,\"active\":240,\"standby\":180,\"state\":\"active\",\"sensor\":1}]}}",
                "{\"key\":\"tools\",\"result\":[{\"number\":0,\"heaters\":[1]}]}");
        expectDisconnect();

        RrfStatusResponse response = client.getStatus(endpoint());

        assertThat(response.stateStatus()).isEqualTo("processing");
        assertThat(response.filename()).isEqualTo("demo.gcode");
        assertThat(response.progress()).isEqualByComparingTo("42.50");
        assertThat(response.filePosition()).isEqualByComparingTo("425");
        assertThat(response.fileSize()).isEqualByComparingTo("1000");
        assertThat(response.timesLeft()).isEqualByComparingTo("12");
        assertThat(response.toolTemperature()).isEqualByComparingTo("237.3");
        assertThat(response.toolTarget()).isEqualByComparingTo("240");
        assertThat(response.bedTemperature()).isEqualByComparingTo("55.4");
        assertThat(response.bedTarget()).isEqualByComparingTo("60");
        assertThat(response.deviceUptime()).isEqualByComparingTo("1234");
        assertThat(response.lastFileCancelled()).isFalse();
        assertThat(response.lastFileAborted()).isFalse();
        server.verify();
    }

    @Test
    void retriesStatusAfterTransientConnectionFailure() {
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_connect")))
                .andExpect(method(GET))
                .andRespond(withException(new ConnectException("device temporarily unavailable")));
        expectConnect();
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_model")))
                .andExpect(method(GET))
                .andExpect(queryParam("key", "state"))
                .andExpect(header("X-Session-Key", "123"))
                .andRespond(withSuccess("{\"key\":\"state\",\"result\":{\"status\":\"idle\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_model")))
                .andExpect(method(GET))
                .andExpect(queryParam("key", "job"))
                .andExpect(header("X-Session-Key", "123"))
                .andRespond(withSuccess("{\"key\":\"job\",\"result\":{}}", MediaType.APPLICATION_JSON));
        expectEmptyTemperatureModels();
        expectDisconnect();

        RrfStatusResponse response = client.getStatus(endpoint());

        assertThat(response.stateStatus()).isEqualTo("idle");
        server.verify();
    }

    @Test
    void sendsGcodeUsingAuthenticatedSession() {
        expectConnect();
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_gcode")))
                .andExpect(method(GET))
                .andExpect(queryParam("gcode", "M25"))
                .andExpect(header("X-Session-Key", "123"))
                .andRespond(withSuccess("{\"err\":0,\"buff\":1024}", MediaType.APPLICATION_JSON));
        expectDisconnect();

        client.executeGcode(endpoint(), "M25", PrinterOperation.PAUSE);

        server.verify();
    }

    @Test
    void readsMacFromNetworkInterfaceMatchingScannedIp() {
        expectConnect();
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_model")))
                .andExpect(method(GET))
                .andExpect(queryParam("key", "network"))
                .andExpect(queryParam("flags", "vn"))
                .andExpect(header("X-Session-Key", "123"))
                .andRespond(withSuccess("""
                        {"key":"network","result":{"interfaces":[
                          {"actualIP":null,"mac":"02:00:4F:9E:27:DE","state":"disabled","type":"ethernet"},
                          {"actualIP":"192.168.1.80","mac":"5C:8E:8B:10:CB:FD","state":"active","type":"wifi"}
                        ]}}
                        """, MediaType.APPLICATION_JSON));
        expectDisconnect();

        RrfApiClient.RrfNetworkIdentity identity = client.getNetworkIdentity(endpoint());

        assertThat(identity).isNotNull();
        assertThat(identity.actualIp()).isEqualTo("192.168.1.80");
        assertThat(identity.macAddress()).isEqualTo("5C:8E:8B:10:CB:FD");
        server.verify();
    }

    @Test
    void acceptsOfficialBufferOnlyGcodeResponse() {
        expectConnect();
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_gcode")))
                .andExpect(method(GET))
                .andExpect(queryParam("gcode", "M32%20%220:/gcodes/demo.gcode%22"))
                .andExpect(header("X-Session-Key", "123"))
                .andRespond(withSuccess("{\"buff\":1024}", MediaType.APPLICATION_JSON));
        expectDisconnect();

        client.executeGcode(endpoint(), "M32 \"0:/gcodes/demo.gcode\"", PrinterOperation.START_PRINT);

        server.verify();
    }

    @Test
    void allowsEmptyPasswordWhenRrfDeviceDoesNotRequireOne() {
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_connect")))
                .andExpect(method(GET))
                .andExpect(queryParam("password", ""))
                .andExpect(queryParam("sessionKey", "yes"))
                .andRespond(withSuccess("{\"err\":0,\"sessionKey\":123}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_gcode")))
                .andExpect(method(GET))
                .andExpect(queryParam("gcode", "M25"))
                .andExpect(header("X-Session-Key", "123"))
                .andRespond(withSuccess("{\"err\":0,\"buff\":1024}", MediaType.APPLICATION_JSON));
        expectDisconnect();

        client.executeGcode(emptyPasswordEndpoint(), "M25", PrinterOperation.PAUSE);

        server.verify();
    }

    @Test
    void supportsSuccessfulRrfResponseWithoutSessionKey() {
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_connect")))
                .andExpect(method(GET))
                .andExpect(queryParam("password", ""))
                .andRespond(withSuccess("{\"err\":0,\"isEmulated\":true}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_model")))
                .andExpect(method(GET))
                .andExpect(queryParam("key", "state"))
                .andRespond(withSuccess("{\"key\":\"state\",\"result\":{\"status\":\"off\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_model")))
                .andExpect(method(GET))
                .andExpect(queryParam("key", "job"))
                .andRespond(withSuccess("{\"key\":\"job\",\"result\":{\"filePosition\":0}}",
                        MediaType.APPLICATION_JSON));
        expectEmptyTemperatureModels();
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_disconnect")))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"err\":0}", MediaType.APPLICATION_JSON));

        RrfStatusResponse response = client.getStatus(emptyPasswordEndpoint());

        assertThat(response.stateStatus()).isEqualTo("off");
        server.verify();
    }

    @Test
    void uploadsRawFileToRrfGcodesDirectory() {
        expectConnect();
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_upload")))
                .andExpect(method(POST))
                .andExpect(queryParam("name", "0:/gcodes/demo.gcode"))
                .andExpect(header("X-Session-Key", "123"))
                .andExpect(content().bytes("G1 X1\n".getBytes(StandardCharsets.UTF_8)))
                .andRespond(withSuccess("{\"err\":0}", MediaType.APPLICATION_JSON));
        expectDisconnect();

        client.uploadFile(endpoint(), new ByteArrayResource("G1 X1\n".getBytes(StandardCharsets.UTF_8)),
                "folder/demo.gcode", false);

        server.verify();
    }

    @Test
    void rejectsInvalidRrfPasswordResponse() {
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_connect")))
                .andExpect(method(GET))
                .andRespond(withSuccess("{\"err\":1}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.executeGcode(endpoint(), "M25", PrinterOperation.PAUSE))
                .isInstanceOf(PrinterProtocolException.class)
                .satisfies(error -> {
                    PrinterProtocolException exception = (PrinterProtocolException) error;
                    assertThat(exception.getCategory()).isEqualTo(FailureCategory.REJECTED);
                    assertThat(exception.getOperation()).isEqualTo(PrinterOperation.PAUSE);
                });
        server.verify();
    }

    @Test
    void rejectsGcodeWhenRrfReturnsAnError() {
        expectConnect();
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_gcode")))
                .andExpect(method(GET))
                .andExpect(queryParam("gcode", "M25"))
                .andRespond(withSuccess("{\"err\":1}", MediaType.APPLICATION_JSON));
        expectDisconnect();

        assertThatThrownBy(() -> client.executeGcode(endpoint(), "M25", PrinterOperation.PAUSE))
                .isInstanceOf(PrinterProtocolException.class)
                .satisfies(error -> {
                    PrinterProtocolException exception = (PrinterProtocolException) error;
                    assertThat(exception.getCategory()).isEqualTo(FailureCategory.REJECTED);
                    assertThat(exception.getOperation()).isEqualTo(PrinterOperation.PAUSE);
                });
        server.verify();
    }

    private void expectConnect() {
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_connect")))
                .andExpect(method(GET))
                .andExpect(queryParam("password", "device-password"))
                .andExpect(queryParam("sessionKey", "yes"))
                .andRespond(withSuccess("{\"err\":0,\"sessionKey\":123}", MediaType.APPLICATION_JSON));
    }

    private void expectDisconnect() {
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_disconnect")))
                .andExpect(method(GET))
                .andExpect(header("X-Session-Key", "123"))
                .andRespond(withSuccess("{\"err\":0}", MediaType.APPLICATION_JSON));
    }

    private void expectEmptyTemperatureModels() {
        expectTemperatureModels(
                "{\"key\":\"heat\",\"result\":{\"heaters\":[]}}",
                "{\"key\":\"tools\",\"result\":[]}");
    }

    private void expectTemperatureModels(String heatResponse, String toolsResponse) {
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_model")))
                .andExpect(method(GET))
                .andExpect(queryParam("key", "heat"))
                .andRespond(withSuccess(heatResponse, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith("http://192.168.1.80/rr_model")))
                .andExpect(method(GET))
                .andExpect(queryParam("key", "tools"))
                .andRespond(withSuccess(toolsResponse, MediaType.APPLICATION_JSON));
    }

    private PrinterEndpoint endpoint() {
        return new PrinterEndpoint(403L, "192.168.1.80", "device-password", PrinterProtocolType.RRF);
    }

    private PrinterEndpoint emptyPasswordEndpoint() {
        return new PrinterEndpoint(403L, "192.168.1.80", "", PrinterProtocolType.RRF);
    }
}

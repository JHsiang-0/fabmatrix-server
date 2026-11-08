package com.example.farm.protocol;

import com.example.farm.common.utils.MoonrakerApiClient;
import com.example.farm.protocol.observation.PrinterObservation.HeaterRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Spring mock transports only; no loopback listener, production address, or printer is contacted. */
class HeaterProtocolControlTest {
    @Test void klipperProbesLimitsOnDemandAndWritesOnlyTheWhitelistedHeater() {
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var client = new MoonrakerApiClient(); ReflectionTestUtils.setField(client, "restClient", builder.build());
        var endpoint = new PrinterEndpoint(1L, "192.0.2.10", "synthetic-api-key", PrinterProtocolType.KLIPPER);
        server.expect(requestTo("http://192.0.2.10:7125/printer/objects/query?configfile=settings"))
                .andExpect(header("X-Api-Key", "synthetic-api-key"))
                .andRespond(withSuccess("{\"result\":{\"status\":{\"configfile\":{\"settings\":{\"extruder\":{\"min_temp\":0,\"max_temp\":280}}}}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://192.0.2.10:7125/printer/gcode/script")).andExpect(method(POST))
                .andExpect(content().json("{\"script\":\"SET_HEATER_TEMPERATURE HEATER=extruder TARGET=205\"}"))
                .andRespond(withSuccess("{\"result\":\"ok\"}", MediaType.APPLICATION_JSON));
        var channel = client.inspectHeater(endpoint, "extruder");
        assertThat(channel.maxC()).isEqualByComparingTo("280"); client.setHeaterTarget(endpoint, channel, 205);
        assertThatThrownBy(() -> client.inspectHeater(endpoint, "heater_generic chamber")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.inspectHeater(endpoint, "extruder\nM112")).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    @Test void rrfBedUsesTheLogicalBedSlotRatherThanPhysicalHeaterIndex() {
        var f = new RrfFixture();
        f.connect();
        f.model("heat", "{\"bedHeaters\":[2],\"heaters\":[{}, {}, {\"min\":0,\"max\":125}]}");
        f.model("tools", "[{\"number\":3,\"heaters\":[1]}]"); f.disconnect();
        f.connect(); f.gcode("M140 P0 S60", false); f.disconnect();
        var channel = f.client.inspectHeater(f.endpoint, "heater-2");
        assertThat(channel.bedSlot()).isEqualTo(0); assertThat(channel.maxC()).isEqualByComparingTo("125");
        f.client.setHeaterTarget(f.endpoint, channel, 60); f.server.verify();
    }

    @ParameterizedTest @ValueSource(ints = {205, 0})
    void rrfUsesTheUniqueToolWithoutSelectingItOrChangingOtherChannels(int target) {
        var f = new RrfFixture(); f.connect();
        f.model("heat", "{\"bedHeaters\":[0],\"heaters\":[{}, {\"min\":0,\"max\":290}]}");
        f.model("tools", "[{\"number\":3,\"heaters\":[1]}]"); f.disconnect();
        f.connect(); f.gcode("M568 P3 S" + target + " A" + (target == 0 ? 0 : 2), false); f.disconnect();
        var channel = f.client.inspectHeater(f.endpoint, "heater-1");
        assertThat(channel.toolNumber()).isEqualTo(3);
        f.client.setHeaterTarget(f.endpoint, channel, target); f.server.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"[{\"number\":0,\"heaters\":[1,2]}]", "[{\"number\":0,\"heaters\":[1]},{\"number\":1,\"heaters\":[1]}]"})
    void sharedOrMultiHeaterToolMappingsAreRejected(String tools) {
        var f = new RrfFixture(); f.connect();
        f.model("heat", "{\"bedHeaters\":[0],\"heaters\":[{}, {\"min\":0,\"max\":300},{}]}");
        f.model("tools", tools); f.disconnect();
        assertThatThrownBy(() -> f.client.inspectHeater(f.endpoint, "heater-1")).isInstanceOf(IllegalArgumentException.class);
        f.server.verify();
    }

    @Test void unknownBetaBedMappingIsNeverGuessedForWriting() {
        var f = new RrfFixture(); f.connect();
        f.model("heat", "{\"bedHeaters\":[-1],\"heaters\":[{\"min\":0,\"max\":120},{}]}");
        f.model("tools", "[{\"number\":0,\"heaters\":[1]}]"); f.disconnect();
        assertThatThrownBy(() -> f.client.inspectHeater(f.endpoint, "heater-0")).isInstanceOf(IllegalArgumentException.class);
        f.server.verify();
    }

    @Test void rrfUnconfirmedWriteIsNotAutomaticallyRetried() {
        var f = new RrfFixture(); f.connect(); f.gcode("M568 P3 S205 A2", true); f.disconnect();
        assertThatThrownBy(() -> f.client.setHeaterTarget(f.endpoint,
                new HeaterControlChannel("heater-1", HeaterRole.TOOL, BigDecimal.ZERO, BigDecimal.valueOf(300), 3, null), 205))
                .isInstanceOf(PrinterProtocolException.class);
        f.server.verify();
    }

    private static final class RrfFixture {
        final MockRestServiceServer server;
        final RrfApiClient client;
        final PrinterEndpoint endpoint = new PrinterEndpoint(1L, "192.0.2.11", "", PrinterProtocolType.RRF);
        RrfFixture() {
            var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build(); client = new RrfApiClient(builder.build());
        }
        void connect() { server.expect(requestTo(startsWith("http://192.0.2.11/rr_connect")))
                .andRespond(withSuccess("{\"err\":0,\"sessionKey\":123}", MediaType.APPLICATION_JSON)); }
        void disconnect() { server.expect(requestTo(startsWith("http://192.0.2.11/rr_disconnect")))
                .andRespond(withSuccess("{\"err\":0}", MediaType.APPLICATION_JSON)); }
        void model(String key, String result) { server.expect(requestTo(startsWith("http://192.0.2.11/rr_model")))
                .andExpect(queryParam("key", key)).andRespond(withSuccess("{\"result\":" + result + "}", MediaType.APPLICATION_JSON)); }
        void gcode(String value, boolean fail) {
            var expectation = server.expect(requestTo(startsWith("http://192.0.2.11/rr_gcode"))).andExpect(queryParam("gcode", value.replace(" ", "%20")));
            expectation.andRespond(fail ? withStatus(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE) : withSuccess("{\"buff\":1000}", MediaType.APPLICATION_JSON));
        }
    }
}

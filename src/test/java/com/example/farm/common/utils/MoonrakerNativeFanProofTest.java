package com.example.farm.common.utils;

import com.example.farm.protocol.PrinterEndpoint;
import com.example.farm.protocol.PrinterProtocolType;
import com.example.farm.protocol.observation.PrinterObservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Mock transport only: optional configuration evidence must never interrupt ordinary monitoring. */
class MoonrakerNativeFanProofTest {
    @Test void defaultConfigMakesRequestedPwmReliableAndRepeatedStatusDoesNotReloadTheFullConfig() {
        var f = new Fixture(); f.query("ready", 100); f.info(12); f.config("{\"max_power\":1,\"off_below\":0}"); f.query("ready", 101);
        var first = f.client.getPrinterStatus(f.endpoint).getObservation().fans().getFirst();
        assertThat(first.requestedPwmRatio().quality()).isEqualTo(PrinterObservation.Quality.GOOD);
        assertThat(first.requestedPwmRatio().value()).isEqualByComparingTo("0.4");
        assertThat(f.client.getPrinterStatus(f.endpoint).getObservation().fans().getFirst().requestedPwmRatio().quality()).isEqualTo(PrinterObservation.Quality.GOOD);
        f.server.verify();
    }

    @ParameterizedTest @ValueSource(strings = {"{\"max_power\":0.8,\"off_below\":0}", "{\"max_power\":1,\"off_below\":0.1}", "{}"})
    void scaledThresholdedOrUnknownConfigKeepsActualPwmButDisablesTheRequestedSetpoint(String settings) {
        var f = new Fixture(); f.query("ready", 100); f.info(12); f.config(settings);
        var fan = f.client.getPrinterStatus(f.endpoint).getObservation().fans().getFirst();
        assertThat(fan.pwmRatio().value()).isEqualByComparingTo("0.4");
        assertThat(fan.requestedPwmRatio().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
        f.server.verify();
    }

    @Test void configFailureIsNegativelyCachedAndOnlyDisablesOptionalFanEvidence() {
        var f = new Fixture(); f.query("ready", 100); f.info(12);
        f.server.expect(requestTo(f.base + "/printer/objects/query?configfile=settings")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        f.query("ready", 101);
        assertThat(f.client.getPrinterStatus(f.endpoint).getObservation().fans().getFirst().requestedPwmRatio().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
        assertThat(f.client.getPrinterStatus(f.endpoint).getSystemState()).isEqualTo("ready"); f.server.verify();
    }

    @Test void missingBootEvidenceCannotAuthorizeFanButDoesNotFailTheDeviceSnapshot() {
        var f = new Fixture(); f.query("ready", 100);
        f.server.expect(requestTo(f.base + "/printer/info")).andRespond(withSuccess("{\"result\":{\"state\":\"ready\"}}", MediaType.APPLICATION_JSON));
        assertThat(f.client.getPrinterStatus(f.endpoint).getObservation().fans().getFirst().requestedPwmRatio().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
        f.server.verify();
    }

    @Test void eventtimeRollbackAndObservedShutdownInvalidateCachedProofImmediately() {
        var f = new Fixture(); f.query("ready", 100); f.info(12); f.config("{\"max_power\":1,\"off_below\":0}");
        f.query("ready", 1); f.info(12); f.config("{\"max_power\":0.8,\"off_below\":0}");
        assertThat(f.client.getPrinterStatus(f.endpoint).getObservation().fans().getFirst().requestedPwmRatio().quality()).isEqualTo(PrinterObservation.Quality.GOOD);
        assertThat(f.client.getPrinterStatus(f.endpoint).getObservation().fans().getFirst().requestedPwmRatio().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
        f.server.verify();

        var g = new Fixture(); g.query("ready", 100); g.info(12); g.config("{\"max_power\":1,\"off_below\":0}");
        g.query("shutdown", 101); g.query("ready", 102); g.info(12); g.config("{\"max_power\":1,\"off_below\":0.1}");
        g.client.getPrinterStatus(g.endpoint); g.client.getPrinterStatus(g.endpoint);
        assertThat(g.client.getPrinterStatus(g.endpoint).getObservation().fans().getFirst().requestedPwmRatio().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
        g.server.verify();
    }

    @Test void changedProcessAndCredentialMustObtainTheirOwnFreshConfigurationProof() {
        var f = new Fixture(); f.query("ready", 100); f.info(12); f.config("{\"max_power\":1,\"off_below\":0}");
        f.query("ready", 101); f.info(13); f.config("{\"max_power\":0.8,\"off_below\":0}");
        f.query("ready", 102); f.info(13); f.config("{\"max_power\":1,\"off_below\":0}");
        f.client.getPrinterStatus(f.endpoint); f.clock.addAndGet(1100);
        assertThat(f.client.getPrinterStatus(f.endpoint).getObservation().fans().getFirst().requestedPwmRatio().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
        var changed = new PrinterEndpoint(1L, f.endpoint.ipAddress(), "different-test-key", PrinterProtocolType.KLIPPER);
        assertThat(f.client.getPrinterStatus(changed).getObservation().fans().getFirst().requestedPwmRatio().quality()).isEqualTo(PrinterObservation.Quality.GOOD);
        f.server.verify();
    }

    @Test void expiredEvidenceIsRefreshedEvenWithoutAVisibleBootChange() {
        var f = new Fixture(); f.query("ready", 100); f.info(12); f.config("{\"max_power\":1,\"off_below\":0}");
        f.query("ready", 101); f.info(12); f.config("{\"max_power\":0.8,\"off_below\":0}");
        f.client.getPrinterStatus(f.endpoint); f.clock.addAndGet(61000);
        assertThat(f.client.getPrinterStatus(f.endpoint).getObservation().fans().getFirst().requestedPwmRatio().quality()).isEqualTo(PrinterObservation.Quality.UNAVAILABLE);
        f.server.verify();
    }

    @Test void privateEvidenceCacheHasABoundedCapacityAcrossDifferentEndpoints() {
        var f = new Fixture();
        for (int id = 0; id < 257; id++) {
            f.server.expect(requestTo(f.base + "/printer/objects/query?gcode_move&toolhead&fan"))
                    .andRespond(withSuccess("{\"result\":{\"status\":{\"fan\":{\"speed\":0.4}}}}", MediaType.APPLICATION_JSON));
            f.info(12); f.config("{\"max_power\":1,\"off_below\":0}");
        }
        for (int id = 0; id < 257; id++) f.client.inspectRuntimeControl(new PrinterEndpoint((long) id, "192.0.2.10", "synthetic-test-key", PrinterProtocolType.KLIPPER), "SET_FAN_SPEED", "fan", null);
        assertThat((java.util.Map<?, ?>) ReflectionTestUtils.getField(f.client, "nativeFanProofs")).hasSize(256);
        f.server.verify();
    }

    @Test void writingAlwaysRechecksTheFirmwareConfigAndCannotUseAnOldDefaultProof() {
        var f = new Fixture(); f.query("ready", 100); f.info(12); f.config("{\"max_power\":1,\"off_below\":0}");
        f.server.expect(requestTo(f.base + "/printer/objects/query?gcode_move&toolhead&fan"))
                .andRespond(withSuccess("{\"result\":{\"status\":{\"fan\":{\"speed\":0.4}}}}", MediaType.APPLICATION_JSON));
        f.info(12); f.config("{\"max_power\":0.8,\"off_below\":0}");
        f.client.getPrinterStatus(f.endpoint);
        assertThatThrownBy(() -> f.client.inspectRuntimeControl(f.endpoint, "SET_FAN_SPEED", "fan", null)).isInstanceOf(IllegalArgumentException.class);
        f.server.verify();
    }

    private static final class Fixture {
        final String base = "http://192.0.2.10:7125";
        final MockRestServiceServer server; final MoonrakerApiClient client = new MoonrakerApiClient();
        final PrinterEndpoint endpoint = new PrinterEndpoint(1L, "192.0.2.10", "synthetic-test-key", PrinterProtocolType.KLIPPER);
        final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1000);
        boolean listed;
        Fixture() { var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).build(); ReflectionTestUtils.setField(client, "restClient", builder.build());
            ReflectionTestUtils.setField(client, "fanClock", (java.util.function.LongSupplier) clock::get); }
        void query(String state, int time) {
            if (!listed) { server.expect(requestTo(base + "/printer/objects/list")).andExpect(header("X-Api-Key", endpoint.apiKey()))
                    .andRespond(withSuccess("{\"result\":{\"objects\":[\"webhooks\",\"print_stats\",\"display_status\",\"fan\"]}}", MediaType.APPLICATION_JSON)); listed = true; }
            server.expect(requestTo(base + "/printer/objects/query")).andRespond(withSuccess("{\"result\":{\"eventtime\":" + time
                    + ",\"status\":{\"webhooks\":{\"state\":\"" + state + "\"},\"print_stats\":{\"state\":\"standby\"},\"display_status\":{},\"fan\":{\"speed\":0.4}}}}", MediaType.APPLICATION_JSON));
        }
        void info(int pid) { server.expect(requestTo(base + "/printer/info")).andRespond(withSuccess("{\"result\":{\"state\":\"ready\",\"process_id\":" + pid + "}}", MediaType.APPLICATION_JSON)); }
        void config(String value) { server.expect(requestTo(base + "/printer/objects/query?configfile=settings"))
                .andRespond(withSuccess("{\"result\":{\"status\":{\"configfile\":{\"settings\":{\"fan\":" + value + "}}}}}", MediaType.APPLICATION_JSON)); }
    }
}

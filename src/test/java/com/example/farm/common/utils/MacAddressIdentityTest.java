package com.example.farm.common.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MacAddressIdentityTest {

    private final MacAddressUtil util = new MacAddressUtil();
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"5C-8E-8B-10-CB-FD", "5C:8E:8B:10:CB:FD", "5C8E8B10CBFD", " 5c:8e:8b:10:cb:fd "})
    void normalizesOnlyCompleteValidUnicastMacs(String mac) {
        assertThat(util.normalizeMacAddress(mac)).isEqualTo("5c:8e:8b:10:cb:fd");
    }

    @ParameterizedTest
    @ValueSource(strings = {"00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "01:00:5e:00:00:01",
            "33:33:00:00:00:01", "MAC=5c:8e:8b:10:cb:fd", "5c:8e:8b:10:cb:fd-more",
            "5c:8e-8b:10:cb:fd", "5c 8e 8b 10 cb fd", "5c:8e:8b:10:cb", ""})
    void rejectsInvalidOrNonDeviceMacs(String mac) {
        assertThat(util.normalizeMacAddress(mac)).isNull();
    }

    @Test
    void windowsArpParsesExactDataRowAcrossInterfaces() {
        String output = """
                Interface: 192.168.1.50 --- 0x8 02-aa-bb-cc-dd-ee
                  Internet Address      Physical Address      Type
                  192.168.1.1           02-00-00-00-00-01     dynamic
                  192.168.1.180         02-00-00-00-00-18     dynamic
                接口: 10.1.1.5 --- 0x6
                  192.168.1.80          5c-8e-8b-10-cb-fd     动态
                  224.0.0.22            01-00-5e-00-00-16     static
                """;

        assertThat(util.extractMacFromArpOutput("192.168.1.80", output)).isEqualTo("5c:8e:8b:10:cb:fd");
        assertThat(util.extractMacFromArpOutput("192.168.1.50", output)).isNull();
        assertThat(util.extractMacFromArpOutput("192.168.1.8", output)).isNull();
    }

    @Test
    void linuxArpParsesExactParenthesizedTarget() {
        String output = """
                router (192.168.1.1) at 02:00:00:00:00:01 [ether] on eth0
                ? (192.168.1.180) at 02:00:00:00:00:18 [ether] on eth0
                printer (192.168.1.80) at 5c:8e:8b:10:cb:fd [ether] on wlan0
                """;

        assertThat(util.extractMacFromArpOutput("192.168.1.80", output)).isEqualTo("5c:8e:8b:10:cb:fd");
    }

    @Test
    void linuxNumericArpParsesTarget() {
        assertThat(util.extractMacFromArpOutput("192.168.1.80",
                "192.168.1.80 ether 5c:8e:8b:10:cb:fd C eth0")).isEqualTo("5c:8e:8b:10:cb:fd");
    }

    @Test
    void arpRejectsAmbiguousTargetMacsAcrossInterfaces() {
        assertThat(util.extractMacFromArpOutput("192.168.1.80", """
                192.168.1.80 5c-8e-8b-10-cb-fd dynamic
                192.168.1.80 02-00-00-00-00-80 dynamic
                """)).isNull();
    }

    @Test
    void arpAcceptsRepeatedIdenticalTarget() {
        assertThat(util.extractMacFromArpOutput("192.168.1.80", """
                192.168.1.80 5c-8e-8b-10-cb-fd dynamic
                ? (192.168.1.80) at 5c:8e:8b:10:cb:fd [ether] on eth0
                """)).isEqualTo("5c:8e:8b:10:cb:fd");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ff-ff-ff-ff-ff-ff", "00-00-00-00-00-00", "01-00-5e-00-00-01"})
    void arpRejectsInvalidTargetMac(String mac) {
        assertThat(util.extractMacFromArpOutput("192.168.1.80", "192.168.1.80 " + mac + " static")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"192.168.1.80 -a", "192.168.1.80&whoami", "printer.local", "192.168.1.080",
            "192.168.256.1", "http://192.168.1.80", "::1"})
    void invalidIpCannotLaunchArpOrHttpRequest(String ip) {
        assertThat(MacAddressUtil.isIpv4Address(ip)).isFalse();
        assertThat(util.getMacFromArpTable(ip)).isNull();
        assertThat(util.getMacFromMoonrakerApi(ip, "unused-key")).isNull();
    }

    @Test
    void moonrakerMatchesCurrentIpAddressesArray() throws Exception {
        var response = mapper.readTree("""
                {"result":{"system_info":{"network":{
                  "lo":{"mac_address":"00:00:00:00:00:00","ip_addresses":[{"family":"ipv4","address":"127.0.0.1"}]},
                  "eth0":{"mac_address":"02:00:00:00:00:02","ip_addresses":[{"address":"192.168.1.180"}]},
                  "wlan0":{"mac_address":"5C:8E:8B:10:CB:FD","ip_addresses":[
                    {"family":"ipv6","address":"fe80::1"},{"family":"ipv4","address":"192.168.1.80"}]}
                }}}}
                """);

        assertThat(util.extractMacFromMoonrakerResponse("192.168.1.80", response)).isEqualTo("5c:8e:8b:10:cb:fd");
        assertThat(util.extractMacFromMoonrakerResponse("192.168.1.8", response)).isNull();
    }

    @Test
    void moonrakerStillMatchesLegacySingleIpField() throws Exception {
        var response = mapper.readTree("""
                {"result":{"system_info":{"network":{"eth0":{
                  "mac_address":"5c:8e:8b:10:cb:fd","ip_address":"192.168.1.80"
                }}}}}
                """);
        assertThat(util.extractMacFromMoonrakerResponse("192.168.1.80", response)).isEqualTo("5c:8e:8b:10:cb:fd");
    }

    @Test
    void moonrakerCannotUseAnotherInterfaceMacOrAmbiguousTarget() throws Exception {
        var response = mapper.readTree("""
                {"result":{"system_info":{"network":{
                  "eth0":{"mac_address":"5c:8e:8b:10:cb:fd","ip_addresses":[{"address":"192.168.1.80"}]},
                  "wlan0":{"mac_address":"02:00:00:00:00:80","ip_address":"192.168.1.80"}
                }}}}
                """);
        assertThat(util.extractMacFromMoonrakerResponse("192.168.1.80", response)).isNull();
        assertThat(util.extractMacFromMoonrakerResponse("192.168.1.1", response)).isNull();
    }
}

package com.example.farm.common.exception;

import com.example.farm.common.api.ResultCode;
import com.example.farm.protocol.FailureCategory;
import com.example.farm.protocol.PrinterOperation;
import com.example.farm.protocol.PrinterProtocolException;
import com.example.farm.protocol.PrinterProtocolType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test");

    @Test
    void preservesBusinessCodeAndMapsPrinterOfflineTo503() {
        var response = handler.handleBusinessException(new BusinessException(ResultCode.PRINTER_OFFLINE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.PRINTER_OFFLINE.getCode());
    }

    @Test
    void mapsInvalidStateTo422() {
        var response = handler.handleBusinessException(new BusinessException(422, "任务当前状态不允许操作"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(422);
    }

    @Test
    void mapsUnsupportedMethodTo405() {
        var exception = new HttpRequestMethodNotSupportedException("GET", java.util.List.of("POST"));
        var response = handler.handleMethodNotSupported(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED.value());
    }

    @Test
    void mapsProtocolOfflineToPrinterOffline() {
        var exception = new PrinterProtocolException(
                PrinterOperation.PAUSE,
                PrinterProtocolType.KLIPPER,
                FailureCategory.OFFLINE,
                "设备无法连接");

        var response = handler.handlePrinterProtocolException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.PRINTER_OFFLINE.getCode());
    }

    @Test
    void mapsUnsupportedProtocolToStableBusinessCode() {
        var exception = new PrinterProtocolException(
                PrinterOperation.GET_STATUS,
                PrinterProtocolType.RRF,
                FailureCategory.UNSUPPORTED,
                "协议暂未接入");

        var response = handler.handlePrinterProtocolException(exception, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.PRINTER_PROTOCOL_UNSUPPORTED.getCode());
    }

    @Test
    void mapsRedisFailureTo503() {
        var response = handler.handleRedisConnectionFailureException(
                new RedisConnectionFailureException("redis down"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.REDIS_ERROR.getCode());
    }

    @Test
    void mapsStorageFailureTo503() {
        var response = handler.handleStorageException(
                new StorageException("storage down"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(ResultCode.STORAGE_ERROR.getCode());
    }
}

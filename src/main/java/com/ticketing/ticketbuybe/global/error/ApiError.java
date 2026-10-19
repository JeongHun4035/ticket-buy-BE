package com.ticketing.ticketbuybe.global.error;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;

public class ApiError extends RuntimeException {
    private final int status;
    private final String code;

    public ApiError(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public record Body(String code, String message, Map<String, Object> details, String traceId) {}

    public static Body body(String code, String message, HttpServletRequest request) {
        Object trace = request.getAttribute("traceId");
        return new Body(code, message, Map.of(), trace == null ? UUID.randomUUID().toString() : trace.toString());
    }
}

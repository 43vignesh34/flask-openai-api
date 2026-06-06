package com.example.flaskopenaiapi.exception;

public class ApiStatusException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    public ApiStatusException(int statusCode, String responseBody) {
        super("OpenAI API error with status code: " + statusCode);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }
}

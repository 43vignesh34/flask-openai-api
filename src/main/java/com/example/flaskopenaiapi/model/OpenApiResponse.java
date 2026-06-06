package com.example.flaskopenaiapi.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OpenApiResponse {
    @JsonProperty("output_text")
    private String outputText;

    public OpenApiResponse() {}

    public OpenApiResponse(String outputText) {
        this.outputText = outputText;
    }

    public String getOutputText() {
        return outputText;
    }

    public void setOutputText(String outputText) {
        this.outputText = outputText;
    }
}

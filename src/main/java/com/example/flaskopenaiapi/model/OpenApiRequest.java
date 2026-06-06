package com.example.flaskopenaiapi.model;

import java.util.List;

public class OpenApiRequest {
    private String model;
    private List<Message> input;

    public OpenApiRequest() {}

    public OpenApiRequest(String model, List<Message> input) {
        this.model = model;
        this.input = input;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public List<Message> getInput() {
        return input;
    }

    public void setInput(List<Message> input) {
        this.input = input;
    }
}

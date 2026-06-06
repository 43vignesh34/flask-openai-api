package com.example.flaskopenaiapi.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenApiResponse {
    private List<OutputItem> output;

    public OpenApiResponse() {}

    public List<OutputItem> getOutput() {
        return output;
    }

    public void setOutput(List<OutputItem> output) {
        this.output = output;
    }

    // Helper method to resolve output text by traversing the nested structure
    public String getOutputText() {
        if (output != null) {
            for (OutputItem item : output) {
                if (item.getContent() != null) {
                    for (ContentBlock cb : item.getContent()) {
                        if ("output_text".equals(cb.getType()) && cb.getText() != null) {
                            return cb.getText();
                        }
                    }
                }
            }
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OutputItem {
        private String type;
        private List<ContentBlock> content;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public List<ContentBlock> getContent() {
            return content;
        }

        public void setContent(List<ContentBlock> content) {
            this.content = content;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ContentBlock {
        private String type;
        private String text;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}

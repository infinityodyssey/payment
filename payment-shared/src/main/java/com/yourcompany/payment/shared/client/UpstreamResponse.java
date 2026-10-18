package com.yourcompany.payment.shared.client;

import tools.jackson.databind.JsonNode;

/**
 * What came back from an upstream, after the transport has read and parsed it.
 *
 * {@code json} is never null: an unparseable or empty body becomes an empty object node,
 * so callers read fields without null-checking the root. {@code raw} keeps the bytes as
 * they arrived, which is what goes to LMS_API_LOG for reconciliation.
 */
public record UpstreamResponse(int httpStatus, String raw, JsonNode json) {

    /** Reads a string field, treating null, missing and blank alike. */
    public String text(String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String asText = value.asString();
        return asText == null || asText.isBlank() ? null : asText;
    }

    public JsonNode at(String... path) {
        JsonNode node = json;
        for (String segment : path) {
            node = node.path(segment);
        }
        return node;
    }
}

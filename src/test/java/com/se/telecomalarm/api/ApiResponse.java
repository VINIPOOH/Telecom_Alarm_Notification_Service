package com.se.telecomalarm.api;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

/**
 * What the test HTTP client got back: status, raw Content-Type header and body text.
 */
record ApiResponse(int status, Optional<String> contentTypeHeader, String body) {

    /**
     * Media type without parameters (charset etc.), lower case; null if the header is absent.
     */
    String mediaType() {
        return contentTypeHeader
                .map(value -> value.split(";")[0].trim().toLowerCase(Locale.ROOT))
                .orElse(null);
    }

    /**
     * Parses the body as JSON; fails if the body is not JSON.
     */
    DocumentContext json() {
        return JsonPath.parse(body);
    }

    /**
     * The body as a JSON object: exactly the keys that are present, nothing added.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> jsonObject() {
        return json().read("$");
    }

    @Override
    public String toString() {
        return "HTTP " + status + " " + contentTypeHeader.orElse("<no Content-Type>") + " " + body;
    }
}

package com.jdbctest.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;

/**
 * Strict JSON for adapter declarations only, without databind or application mapper settings.
 */
final class AdapterJson {

    private static final int MAX_BYTES = 1_048_576;
    private static final JsonFactory JSON = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(64)
            .maxNumberLength(128).maxStringLength(MAX_BYTES).maxNameLength(MAX_BYTES).build())
        .build();

    private AdapterJson() {
    }

    static Object read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("ADAPTER_JSON_TOO_LARGE");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("ADAPTER_JSON_INVALID_UTF8", exception);
        }
        try (JsonParser parser = JSON.createParser(ObjectReadContext.empty(), text)) {
            Object value = value(parser, parser.nextToken());
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException("ADAPTER_JSON_TRAILING_CONTENT");
            }
            return value;
        } catch (tools.jackson.core.JacksonException exception) {
            // Source text can contain local configuration; expose a stable diagnostic only.
            throw new IllegalArgumentException("ADAPTER_JSON_INVALID");
        }
    }

    private static Object value(JsonParser parser, JsonToken token) {
        if (token == null) {
            throw new IllegalArgumentException("ADAPTER_JSON_EMPTY");
        }
        return switch (token) {
            case START_OBJECT -> {
                Map<String, Object> fields = new LinkedHashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String name = parser.currentName();
                    fields.put(name, value(parser, parser.nextToken()));
                }
                yield Collections.unmodifiableMap(fields);
            }
            case START_ARRAY -> {
                List<Object> items = new ArrayList<>();
                JsonToken next;
                while ((next = parser.nextToken()) != JsonToken.END_ARRAY) {
                    items.add(value(parser, next));
                }
                yield Collections.unmodifiableList(items);
            }
            case VALUE_STRING -> parser.getString();
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                if (parser.getStringLength() > 128) {
                    throw new IllegalArgumentException("ADAPTER_JSON_NUMBER_TOO_LONG");
                }
                if (token == JsonToken.VALUE_NUMBER_INT) {
                    yield parser.getBigIntegerValue();
                }
                var number = parser.getDecimalValue();
                if (!Double.isFinite(number.doubleValue())) {
                    throw new IllegalArgumentException("ADAPTER_JSON_NUMBER_OUT_OF_RANGE");
                }
                yield number;
            }
            case VALUE_TRUE -> true;
            case VALUE_FALSE -> false;
            case VALUE_NULL -> null;
            default -> throw new IllegalArgumentException("ADAPTER_JSON_INVALID_TOKEN");
        };
    }
}

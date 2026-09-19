/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

package at.itbh.pdfuagen.core;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Reads template data. The result consists of {@link Map}, {@link List}, {@link String}, {@link
 * java.math.BigDecimal}/{@link java.math.BigInteger}, {@link Boolean} and {@code null}, which Qute
 * resolves without reflection. Decimals are {@code BigDecimal} so amounts keep their exact value.
 */
public final class JsonData {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder().maxNestingDepth(64).build())
                  .build())
          .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
          .build();

  private JsonData() {}

  /** Parses a JSON object. */
  public static Map<String, Object> parse(InputStream in) throws RenderException {
    try {
      Object value = MAPPER.readValue(in, new TypeReference<Object>() {});
      if (!(value instanceof Map<?, ?> map)) {
        throw new RenderException(
            List.of(new Problem(Problem.INVALID_DATA, "Template data must be a JSON object", "#")));
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>) map;
      return result;
    } catch (JsonProcessingException e) {
      String location =
          e.getLocation() == null
              ? null
              : "line " + e.getLocation().getLineNr() + ", column " + e.getLocation().getColumnNr();
      throw new RenderException(
          new Problem(Problem.INVALID_DATA, "Invalid JSON: " + e.getOriginalMessage(), location),
          e);
    } catch (IOException e) {
      throw new RenderException(
          new Problem(Problem.INVALID_DATA, "Cannot read data: " + e.getMessage(), null), e);
    }
  }
}

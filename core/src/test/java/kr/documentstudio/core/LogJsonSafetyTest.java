package kr.documentstudio.core;

import org.apache.logging.log4j.message.MapMessage;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression for CVE-2026-49844: numeric input must never break the JSON envelope. */
public class LogJsonSafetyTest {
    @Test @SuppressWarnings({"rawtypes","unchecked"})
    public void nonFiniteNumbersCannotProduceBareInvalidJsonTokens() {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            MapMessage message = new MapMessage();
            message.put("value", value);
            String json = message.getFormattedMessage(new String[]{"JSON"});
            assertTrue(json, json.matches("\\{\\s*\"value\"\\s*:\\s*(?:null|\"(?:NaN|-?Infinity)\")\\s*\\}"));
        }
        MapMessage finite = new MapMessage();
        finite.put("value", 1.25);
        assertTrue(finite.getFormattedMessage(new String[]{"JSON"}).matches("\\{\\s*\"value\"\\s*:\\s*1\\.25\\s*\\}"));
    }
}

package com.urbantransport.ticketing.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NitUtilsTest {

    @ParameterizedTest
    @CsvSource({"1234567-8, 12345678", "' 123 456-k ', 123456K", "cf, CF", "12345678, 12345678"})
    void normalize_removesSpacesAndHyphensAndUppercases(String raw, String expected) {
        assertEquals(expected, NitUtils.normalize(raw));
    }

    @Test
    void normalize_null_returnsNull() {
        assertNull(NitUtils.normalize(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "123456K", "10"})
    void digitsWithOptionalFinalK_areValid(String nit) {
        assertTrue(NitUtils.isValid(nit));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"1", "K", "CF", "12K4", "1234-5", "123456k"})
    void otherFormats_areInvalid(String nit) {
        assertFalse(NitUtils.isValid(nit));
    }
}

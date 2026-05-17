package com.routor.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OptionTest {
    @Test
    void optionShouldStoreValues() {
        Option option = new Option("email", "identification", "Email", true);

        assertEquals("email", option.getId());
        assertEquals("identification", option.getType());
        assertEquals("Email", option.getDisplayName());
        assertTrue(option.isHasSubSteps());
    }
}

package com.routor.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StateTest {
    @Test
    void stateShouldHaveCorrectValues() {
        assertEquals(3, State.values().length);
        assertNotNull(State.NEED_INPUT);
        assertNotNull(State.COMPLETED);
        assertNotNull(State.ERROR);
    }
}

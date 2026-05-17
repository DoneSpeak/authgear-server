package com.routor.engine;

import com.routor.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class StackExecutorTest {
    private StackExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new StackExecutor();
    }

    @Test
    void shouldInitializeWithSteps() {
        StepDefinition step = new StepDefinition();
        step.setType("identify");
        FlowDefinition flow = new FlowDefinition();
        flow.setSteps(Collections.singletonList(step));

        FlowInstance instance = new FlowInstance();
        executor.initialize(instance, flow);

        assertFalse(instance.getStack().isEmpty());
        assertEquals(0, instance.currentFrame().getStepIndex());
    }

    @Test
    void shouldGetCurrentOptions() {
        BranchDefinition branch = new BranchDefinition();
        branch.setIdentification("email");

        StepDefinition step = new StepDefinition();
        step.setType("identify");
        step.setOneOf(Collections.singletonList(branch));

        FlowInstance instance = new FlowInstance();
        StackFrame frame = new StackFrame(0, Collections.singletonList(step), null);
        instance.getStack().push(frame);

        var options = executor.getCurrentOptions(instance);
        assertEquals(1, options.size());
        assertEquals("email", options.get(0).getId());
    }

    @Test
    void shouldProcessInputAndAdvance() {
        BranchDefinition branch = new BranchDefinition();
        branch.setIdentification("oauth");

        StepDefinition step = new StepDefinition();
        step.setType("identify");
        step.setOneOf(Collections.singletonList(branch));

        FlowInstance instance = new FlowInstance();
        StackFrame frame = new StackFrame(0, Collections.singletonList(step), null);
        instance.getStack().push(frame);

        executor.processInput(instance, "oauth");

        assertEquals(State.COMPLETED, instance.getState());
        assertTrue(instance.getPath().contains("oauth"));
    }
}

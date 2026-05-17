package learning.authflow.core;

import learning.authflow.intent.InputReactor;
import learning.authflow.intent.InputSchema;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Accept 循环中查找 InputReactor 的结果。
 */
@Getter
@AllArgsConstructor
public class AcceptResult {
    private final InputReactor reactor;
    private final InputSchema schema;
}

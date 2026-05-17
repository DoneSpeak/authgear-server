package learning.authflow.intent.impl;

import lombok.Data;

/**
 * 选择索引的输入数据
 */
public class SelectIndexInput {
    private int index;

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }
}

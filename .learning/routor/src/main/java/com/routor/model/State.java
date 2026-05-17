package com.routor.model;

/**
 * 流程执行状态
 */
public enum State {
    NEED_INPUT,     // 需要用户选择
    COMPLETED,      // 流程完成
    ERROR           // 执行错误
}

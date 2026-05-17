package com.routor.model;

/**
 * 用户可选择的选项
 */
public class Option {
    private final String id;
    private final String type;
    private final String displayName;
    private final boolean hasSubSteps;

    public Option(String id, String type, String displayName, boolean hasSubSteps) {
        this.id = id;
        this.type = type;
        this.displayName = displayName;
        this.hasSubSteps = hasSubSteps;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isHasSubSteps() {
        return hasSubSteps;
    }

    @Override
    public String toString() {
        return id;
    }
}

package learning.userprofile.config;

/**
 * Access control level for a profile attribute (per role).
 * Mirrors Authgear: hidden | readonly | readwrite.
 */
public enum AccessControlLevel {
    HIDDEN,
    READONLY,
    READWRITE
}

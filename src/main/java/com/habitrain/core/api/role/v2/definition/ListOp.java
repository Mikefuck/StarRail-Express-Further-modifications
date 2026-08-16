package com.habitrain.core.api.role.v2.definition;

/**
 * Merge operation for a list-valued patch (relations, and later shop/skill lists).
 *
 * <p>{@link #APPEND} concatenates values onto the running list, {@link #REMOVE}
 * drops matching values, and {@link #REPLACE_ALL} discards the running list.
 */
public enum ListOp {
    APPEND,
    REMOVE,
    REPLACE_ALL,
    REPLACE_MATCHING_IDS
}

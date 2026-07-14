package com.talkwithneighbors.entity;

/**
 * Identifies how wall-clock meetup values are stored in {@code chat_rooms}.
 *
 * <p>Rows created before the UTC API cutover have a null value and are treated
 * as {@link #LEGACY_ASIA_SEOUL}. New rows are written as {@link #UTC}. Keeping
 * the marker makes the cutover backward compatible without guessing from the
 * timestamp value itself.</p>
 */
public enum MeetupTimeBasis {
    UTC,
    LEGACY_ASIA_SEOUL
}

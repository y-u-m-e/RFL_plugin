package com.rfl.replay;

import lombok.Value;

/**
 * Where the last replay buffer clip is, for the panel: nothing asked yet, being cut and written,
 * written (with its file, size and length), or failed (with a short reason).
 */
@Value
public class ClipStatus
{
    /** What a clip save is doing. */
    public enum State
    {
        IDLE,
        SAVING,
        SAVED,
        ERROR
    }

    /** Why a saved clip is shorter than asked. */
    public enum Short
    {
        NONE,
        /** The buffer's memory cap dropped the oldest lines. */
        MEMORY_CAP,
        /** Less than that was recorded so far. */
        NOT_ENOUGH
    }

    public static final ClipStatus IDLE = new ClipStatus(State.IDLE, null, 0L, 0, Short.NONE, 0.0, null, 0L);

    State state;
    String fileName;
    /** Compressed size on disk, once saved. */
    long bytes;
    /** The clip's length in seconds, once planned. */
    int seconds;
    Short shortened;
    /** 0..1 while saving. */
    double progress;
    String error;
    /** When the save finished or failed, epoch ms. */
    long atMs;
}

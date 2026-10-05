package com.rfl.replay;

import sh.yumekui.toolkit.io.WriterState;

/**
 * Where the replay file is in its life, for the panel's status strip: nothing open, being
 * recorded, being finished on the writer's thread, just finished, or failed.
 */
public enum ReplayState
{
    IDLE,
    RECORDING,
    SAVING,
    SAVED,
    ERROR;

    /** The replay's state for the writer's: an open file is being recorded. */
    static ReplayState of(WriterState state)
    {
        switch (state)
        {
            case WRITING:
                return RECORDING;
            case SAVING:
                return SAVING;
            case SAVED:
                return SAVED;
            case ERROR:
                return ERROR;
            default:
                return IDLE;
        }
    }
}

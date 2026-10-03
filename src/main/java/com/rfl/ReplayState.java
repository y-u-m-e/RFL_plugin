package com.rfl;

/**
 * Where the replay file is in its life, for the panel's status strip: nothing open, being
 * recorded, being finished on the writer's thread, just finished, or failed.
 */
enum ReplayState
{
    IDLE,
    RECORDING,
    SAVING,
    SAVED,
    ERROR
}

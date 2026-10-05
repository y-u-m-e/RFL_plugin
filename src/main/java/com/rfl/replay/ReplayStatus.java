package com.rfl.replay;

import com.rfl.panel.PanelModel;

import lombok.Value;

/**
 * What the panel's replay card shows, as plain values ({@link PanelModel#replayStrip} formats it).
 * {@code elapsedMs} and {@code models} are meaningful while recording; {@code progress} while
 * saving; {@code savedAtMs} once saved; {@code error} on failure. {@code bytes} is the compressed
 * size on disk so far (the finished size once saved).
 */
@Value
public class ReplayStatus
{
    public static final ReplayStatus IDLE = new ReplayStatus(ReplayState.IDLE, null, 0L, 0L, 0, 0.0, null, 0L);

    ReplayState state;
    String fileName;
    long elapsedMs;
    long bytes;
    int models;
    double progress;
    String error;
    long savedAtMs;
}

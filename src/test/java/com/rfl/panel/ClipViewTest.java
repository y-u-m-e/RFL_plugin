package com.rfl.panel;

import com.rfl.replay.ClipStatus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** {@link PanelModel#clipView}: the Save last button and the clip line in every state. */
public class ClipViewTest
{
    private static final long NOW = 1_000_000L;

    private static ClipStatus saved(ClipStatus.Short shortened, int seconds, long atMs)
    {
        return new ClipStatus(ClipStatus.State.SAVED, "a_b.rflr.gz", 845 * 1024, seconds, shortened, 1.0, null, atMs);
    }

    @Test
    public void theButtonLabelFollowsTheClipLength()
    {
        assertEquals("Save last 3 min", PanelModel.clipButtonText(180));
        assertEquals("Save last 1:30", PanelModel.clipButtonText(90));
        assertEquals("Save last 0:30", PanelModel.clipButtonText(30));
        assertEquals("Save last 5 min", PanelModel.clipButtonText(300));
        assertEquals("Save last 2:15", PanelModel.clipButtonText(135));
    }

    @Test
    public void theButtonShowsWithRecordReplaysOnAndWorksOnlyWhileRecording()
    {
        PanelModel.ClipView off = PanelModel.clipView(ClipStatus.IDLE, false, false, 180, null, NOW);
        assertFalse(off.isButtonVisible());
        PanelModel.ClipView waiting = PanelModel.clipView(ClipStatus.IDLE, true, false, 180, null, NOW);
        assertTrue(waiting.isButtonVisible());
        assertFalse(waiting.isButtonEnabled());
        PanelModel.ClipView recording = PanelModel.clipView(ClipStatus.IDLE, true, true, 90, null, NOW);
        assertTrue(recording.isButtonEnabled());
        assertEquals("Save last 1:30", recording.getButtonText());
        assertEquals(" ", recording.getText());
    }

    @Test
    public void savingShowsProgress()
    {
        ClipStatus saving = new ClipStatus(ClipStatus.State.SAVING, "x.rflr.gz", 0L, 180, ClipStatus.Short.NONE, 0.42,
            null, 0L);
        PanelModel.ClipView view = PanelModel.clipView(saving, true, true, 180, null, NOW);
        assertEquals("Saving clip... 42%", view.getText());
        assertEquals(PanelModel.ClipTone.PROGRESS, view.getTone());
        assertFalse(view.isOpenVisible());
    }

    @Test
    public void savedShowsFileSizeAndLengthWithOpenFolderForAFewSeconds()
    {
        PanelModel.ClipView view = PanelModel.clipView(saved(ClipStatus.Short.NONE, 180, NOW - 1000), true, true, 180,
            null, NOW);
        assertEquals("Saved clip a_b.rflr.gz (845 KB, 3:00)", view.getText());
        assertTrue(view.isOpenVisible());
        assertEquals(PanelModel.ClipTone.OK, view.getTone());
        PanelModel.ClipView later = PanelModel.clipView(saved(ClipStatus.Short.NONE, 180,
            NOW - PanelModel.CLIP_SAVED_SHOW_MS), true, true, 180, null, NOW);
        assertEquals(" ", later.getText());
        assertFalse(later.isOpenVisible());
    }

    @Test
    public void aShortenedClipSaysWhy()
    {
        assertEquals("Saved clip a_b.rflr.gz (845 KB, 2:10, cut short to fit memory)", PanelModel.clipView(
            saved(ClipStatus.Short.MEMORY_CAP, 130, NOW), true, true, 180, null, NOW).getText());
        assertEquals("Saved clip a_b.rflr.gz (845 KB, 1:12, all recorded so far)", PanelModel.clipView(
            saved(ClipStatus.Short.NOT_ENOUGH, 72, NOW), true, true, 180, null, NOW).getText());
    }

    @Test
    public void anErrorShowsItsReason()
    {
        ClipStatus failed = new ClipStatus(ClipStatus.State.ERROR, null, 0L, 0, ClipStatus.Short.NONE, 0.0,
            "access denied", NOW);
        PanelModel.ClipView view = PanelModel.clipView(failed, true, true, 180, null, NOW);
        assertEquals("Couldn't save clip: access denied", view.getText());
        assertEquals(PanelModel.ClipTone.ERROR, view.getTone());
    }

    @Test
    public void theHotkeyWhileNotRecordingShowsANotice()
    {
        PanelModel.ClipView view = PanelModel.clipView(ClipStatus.IDLE, false, false, 180,
            PanelModel.CLIP_NOT_RECORDING, NOW);
        assertEquals(PanelModel.CLIP_NOT_RECORDING, view.getText());
        assertEquals(PanelModel.ClipTone.WARN, view.getTone());
    }
}

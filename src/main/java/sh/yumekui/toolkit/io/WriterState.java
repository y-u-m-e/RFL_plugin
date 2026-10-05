package sh.yumekui.toolkit.io;

/** Where a {@link GzipNdjsonWriter}'s file is in its life. */
public enum WriterState
{
    /** No file opened yet. */
    IDLE,
    /** A file is open and taking lines. */
    WRITING,
    /** Closed by the caller; the lines still queued are being written, then the gzip trailer. */
    SAVING,
    /** The last file is complete on disk. */
    SAVED,
    /** The last file failed; {@link GzipNdjsonWriter#error()} says why. */
    ERROR
}

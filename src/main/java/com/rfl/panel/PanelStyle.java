package com.rfl.panel;

import java.awt.Color;
import net.runelite.client.ui.ColorScheme;
import sh.yumekui.toolkit.swing.SidebarWidgets;

/** The RFL panel's colours, tints and HTML wrapping, shared by its cards and lists. */
final class PanelStyle
{
    /** Collisions' accent: the cards, list rows and selected tile. */
    static final Color COLLISION = ColorScheme.BRAND_ORANGE;
    /** Incompletes' accent, the same cyan as the default incomplete highlight. */
    static final Color INCOMPLETE = new Color(0, 200, 255);
    /** Stop and confirm-to-clear buttons. */
    static final Color ALERT = new Color(200, 40, 40);
    static final Color OK = ColorScheme.PROGRESS_COMPLETE_COLOR;
    /** Record button, idle ("Start recording"): RuneLite's own green, so it reads as an action, not as greyed out. */
    static final Color RECORD_IDLE = ColorScheme.PROGRESS_COMPLETE_COLOR;
    /** Record button, idle under the mouse: the green lifted a little towards white. */
    static final Color RECORD_IDLE_HOVER = SidebarWidgets.blend(RECORD_IDLE, Color.WHITE, 0.35f);
    /** Record button, armed ("Stop recording"): red, so stopping is one obvious click away. */
    static final Color RECORD_STOP = ALERT.brighter();
    /** Record button when it cannot be clicked: a flat mid grey, unlike either live state. */
    static final Color RECORD_DISABLED = ColorScheme.MEDIUM_GRAY_COLOR;
    /** How far a card's background leans towards its accent (0 none, 1 the accent itself). */
    static final float CARD_TINT = 0.18f;
    /** How far a selected list row leans towards its accent. */
    static final float SELECTED_TINT = 0.25f;
    /** Width for wrapped HTML text in a card, in CSS pixels: the sidebar's usable width less padding. */
    static final int WRAP = 165;

    private PanelStyle()
    {
    }

    /**
     * The record button's text colour. Disabled wins over everything; armed is red with no hover
     * change; idle is green, lighter under the mouse.
     */
    static Color recordForeground(boolean armed, boolean enabled, boolean hover)
    {
        if (!enabled)
        {
            return RECORD_DISABLED;
        }
        if (armed)
        {
            return RECORD_STOP;
        }
        return hover ? RECORD_IDLE_HOVER : RECORD_IDLE;
    }

    /** Wrapped HTML at the card width; the argument is already escaped. */
    static String wrap(String escapedHtml)
    {
        return wrap(escapedHtml, WRAP);
    }

    /** Wrapped HTML at {@code widthCss} CSS pixels; the argument is already escaped. */
    static String wrap(String escapedHtml, int widthCss)
    {
        return "<html><body style='width:" + widthCss + "px'>" + escapedHtml + "</body></html>";
    }

    /** The RuneScape fonts have no "↔" glyph, so it is drawn in the logical Dialog font. */
    static String arrows(String html)
    {
        return html.replace("↔", "<font face='Dialog'>&harr;</font>");
    }
}

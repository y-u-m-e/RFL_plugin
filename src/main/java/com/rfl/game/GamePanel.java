package com.rfl.game;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The "RFL" sidebar panel (spec §6): browse, host and join games, then the lobby with host
 * controls. Also owns the poll loop: one request in flight at a time, the next one scheduled on
 * the injected executor after each response ({@link GamePoller#nextDelayMs}).
 *
 * <p>Threads: Swing fields are touched on the EDT only; poll/action callbacks arrive on OkHttp
 * threads, update the thread-safe {@link GameSession}/{@link GamePoller}s, then hop to the EDT.
 * Client state is never read here — the RSN comes from the identity snapshot RflPlugin takes on
 * the client thread.
 */
public final class GamePanel extends PluginPanel
{
    static final String OPT_IN = "Turn on Enable reporting to browse and join games";
    static final String GAME_ENDED = "Game ended";
    static final String REMOVED = "You were removed from the game";
    private static final String NOT_FOUND = "game not found";
    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    private static final String[] TEAM_KEYS = {"", "A", "B"};

    private final GameClient games;
    private final GameSession session;
    private final Supplier<GameClient.Identity> identity;
    private final ScheduledExecutorService executor;
    private final GamePoller<List<GameSummary>> listPoller = new GamePoller<>();
    private final GamePoller<GameDetail> lobbyPoller = new GamePoller<>();
    private final Random random = new Random();

    // Poll loop, guarded by this. A bumped generation orphans the pending/in-flight poll.
    private int generation;
    private ScheduledFuture<?> nextPoll;

    // EDT only.
    private final JPanel content = new JPanel();
    private final List<JComboBox<String>> combos = new ArrayList<>();
    private boolean reporting;
    private String notice = "";
    private String actionError = "";
    /** The passphrase we hosted/joined with; the API never returns it. */
    private String passphrase = "";
    private String rendered;

    public GamePanel(final GameClient games, final GameSession session,
        final Supplier<GameClient.Identity> identity, final ScheduledExecutorService executor)
    {
        this.games = games;
        this.session = session;
        this.identity = identity;
        this.executor = executor;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(content, BorderLayout.NORTH);
        render();
    }

    // ---------------------------------------------------------------- pure logic (unit-tested)

    /**
     * @param detail latest lobby poll
     * @param rsn    our RSN, or null when unknown (logged out) — then roster membership isn't judged
     * @return {@link #GAME_ENDED}, {@link #REMOVED}, or null while we're still in the game
     */
    static String exitNotice(final GameDetail detail, final String rsn)
    {
        if (detail == null || detail.game == null || "ended".equals(detail.game.state))
        {
            return GAME_ENDED;
        }
        if (rsn == null)
        {
            return null;
        }
        if (detail.players != null)
        {
            for (final GameDetail.Player p : detail.players)
            {
                if (rsn.equals(p.rsn))
                {
                    return null;
                }
            }
        }
        return REMOVED;
    }

    /** @return "Start" in the lobby, "End" while live, else null (the host's state button) */
    static String hostStateButton(final String state)
    {
        if ("lobby".equals(state))
        {
            return "Start";
        }
        return "live".equals(state) ? "End" : null;
    }

    /** @return {@code #RRGGBB} parsed, or {@code fallback} when malformed */
    static Color parseColor(final String hex, final Color fallback)
    {
        return hex != null && HEX.matcher(hex).matches() ? Color.decode(hex) : fallback;
    }

    static String toHex(final Color c)
    {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ---------------------------------------------------------------- poll loop

    /** Any thread. Restarts the loop with an immediate poll. */
    public synchronized void startPolling()
    {
        stopPolling();
        schedule(generation, 0);
    }

    /** Any thread. Stops the loop; a poll already in flight is ignored when it lands. */
    public synchronized void stopPolling()
    {
        generation++;
        if (nextPoll != null)
        {
            nextPoll.cancel(false);
            nextPoll = null;
        }
    }

    private synchronized void schedule(final int gen, final long delayMs)
    {
        if (gen == generation)
        {
            nextPoll = executor.schedule(() -> poll(gen), delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleNext(final int gen)
    {
        schedule(gen, lobbyPoller.nextDelayMs(!session.gameId().isEmpty()));
    }

    private void poll(final int gen)
    {
        final String id = session.gameId();
        if (id.isEmpty())
        {
            games.list(r ->
            {
                if (r.isOk())
                {
                    listPoller.onSuccess(r.value());
                }
                else
                {
                    listPoller.onFailure(r.error());
                }
                refresh();
                scheduleNext(gen);
            });
        }
        else
        {
            games.get(id, r ->
            {
                onLobby(id, r);
                refresh();
                scheduleNext(gen);
            });
        }
    }

    /** OkHttp thread. Applies a lobby poll unless we've since left/switched games. */
    private void onLobby(final String id, final GameClient.Result<GameDetail> r)
    {
        if (!id.equals(session.gameId()))
        {
            return;
        }
        if (!r.isOk())
        {
            if (NOT_FOUND.equals(r.error()))
            {
                exitGame(GAME_ENDED);
            }
            else
            {
                lobbyPoller.onFailure(r.error());
            }
            return;
        }
        final String exit = exitNotice(r.value(), rsn());
        if (exit != null)
        {
            exitGame(exit);
            return;
        }
        lobbyPoller.onSuccess(r.value());
        session.update(r.value());
    }

    private void exitGame(final String why)
    {
        session.clear();
        SwingUtilities.invokeLater(() ->
        {
            notice = why;
            passphrase = "";
            restartPolling();
            render();
        });
    }

    private String rsn()
    {
        final GameClient.Identity id = identity.get();
        return id == null ? null : id.rsn;
    }

    // ---------------------------------------------------------------- actions (EDT)

    /** EDT. Shows the opt-in message and stops polling while reporting is off. */
    public void setReporting(final boolean on)
    {
        reporting = on;
        if (on)
        {
            startPolling();
        }
        else
        {
            stopPolling();
        }
        render();
    }

    /** EDT. Joined/left: switch between list and lobby polling now instead of after up to 10 s. */
    private void restartPolling()
    {
        if (reporting)
        {
            startPolling();
        }
    }

    private void hostGame()
    {
        final String me = rsn();
        final JTextField name = new JTextField(me == null ? "" : (me + "'s game"));
        final JTextField pass = new JTextField(Passphrases.generate(random));
        final JPanel form = new JPanel(new GridLayout(0, 1, 0, 2));
        form.add(new JLabel("Game name"));
        form.add(name);
        form.add(new JLabel("Passphrase"));
        form.add(pass);
        if (JOptionPane.showConfirmDialog(this, form, "Host game", JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION)
        {
            return;
        }
        final String pp = pass.getText().trim();
        games.create(name.getText().trim(), pp, r -> entered(r, pp));
    }

    private void joinByPassphrase()
    {
        final String pp = askPassphrase("Join game", "");
        if (pp != null)
        {
            games.joinByPassphrase(pp, r -> entered(r, pp));
        }
    }

    private void joinListed(final GameSummary game)
    {
        final String pp = askPassphrase("Join " + game.name, "");
        if (pp != null)
        {
            games.join(game.id, pp, r -> entered(r.isOk() ? GameClient.Result.ok(game.id) : r, pp));
        }
    }

    /** Any thread. After host/join: fetch the lobby right away and enter it. */
    private void entered(final GameClient.Result<String> r, final String pp)
    {
        if (!r.isOk() || r.value() == null)
        {
            fail(r.isOk() ? "No game id returned" : r.error());
            return;
        }
        games.get(r.value(), d ->
        {
            if (!d.isOk())
            {
                fail(d.error());
                return;
            }
            lobbyPoller.onSuccess(d.value());
            session.update(d.value());
            SwingUtilities.invokeLater(() ->
            {
                passphrase = pp;
                notice = "";
                actionError = "";
                restartPolling();
                render();
            });
        });
    }

    private void leave()
    {
        final String id = session.gameId();
        games.leave(id, r ->
        {
            if (!r.isOk())
            {
                fail(r.error());
                return;
            }
            if (id.equals(session.gameId()))
            {
                session.clear();
            }
            SwingUtilities.invokeLater(() ->
            {
                passphrase = "";
                actionError = "";
                restartPolling();
                render();
            });
        });
    }

    /** EDT. Sends a host action (action + fields flat; GameClient adds installId). */
    private void host(final Map<String, Object> action, final Runnable onOk)
    {
        games.host(session.gameId(), action, r ->
        {
            if (!r.isOk())
            {
                fail(r.error());
                return;
            }
            SwingUtilities.invokeLater(() ->
            {
                actionError = "";
                if (onOk != null)
                {
                    onOk.run();
                }
                render();
            });
        });
    }

    private static Map<String, Object> action(final String name, final Object... kv)
    {
        final Map<String, Object> m = new HashMap<>();
        m.put("action", name);
        for (int i = 0; i < kv.length; i += 2)
        {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private void changePassphrase()
    {
        final String pp = askPassphrase("Change passphrase", Passphrases.generate(random));
        if (pp != null)
        {
            host(action("passphrase", "passphrase", pp), () -> passphrase = pp);
        }
    }

    private String askPassphrase(final String title, final String initial)
    {
        final Object answer = JOptionPane.showInputDialog(this, "Passphrase", title,
            JOptionPane.PLAIN_MESSAGE, null, null, initial);
        final String pp = answer == null ? "" : answer.toString().trim();
        return pp.isEmpty() ? null : pp;
    }

    private boolean confirm(final String message)
    {
        return JOptionPane.showConfirmDialog(this, message, "RFL", JOptionPane.YES_NO_OPTION)
            == JOptionPane.YES_OPTION;
    }

    /** Any thread. */
    private void fail(final String error)
    {
        SwingUtilities.invokeLater(() ->
        {
            actionError = error == null ? "Something went wrong" : error;
            render();
        });
    }

    // ---------------------------------------------------------------- rendering (EDT)

    private void refresh()
    {
        SwingUtilities.invokeLater(this::render);
    }

    /**
     * Rebuilds only when what's shown changed, and never while the host is mid-edit (a typed but
     * unsent team name, or an open team dropdown) so a poll can't wipe it.
     */
    private void render()
    {
        if (editing())
        {
            return;
        }
        final GameDetail detail = session.detail();
        final String key = signature(detail);
        if (key.equals(rendered))
        {
            return;
        }
        rendered = key;
        content.removeAll();
        combos.clear();
        content.add(title("RFL"));
        if (!reporting)
        {
            content.add(text(OPT_IN, ColorScheme.LIGHT_GRAY_COLOR));
        }
        else if (detail == null || detail.game == null)
        {
            buildBrowser();
        }
        else
        {
            buildLobby(detail);
        }
        content.revalidate();
        content.repaint();
    }

    private boolean editing()
    {
        final Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (focus instanceof JTextField && SwingUtilities.isDescendingFrom(focus, this))
        {
            final JTextField field = (JTextField) focus;
            if (!field.getText().equals(field.getClientProperty("sent")))
            {
                return true;
            }
        }
        for (final JComboBox<String> combo : combos)
        {
            if (combo.isPopupVisible())
            {
                return true;
            }
        }
        return false;
    }

    private String signature(final GameDetail d)
    {
        final StringBuilder sb = new StringBuilder()
            .append(reporting).append('|').append(notice).append('|').append(actionError).append('|')
            .append(passphrase).append('|').append(rsn()).append('|');
        if (d == null || d.game == null)
        {
            sb.append(listPoller.lastError()).append('|');
            final List<GameSummary> list = listPoller.data();
            if (list != null)
            {
                for (final GameSummary g : list)
                {
                    sb.append(g.id).append(g.name).append(g.hostRsn).append(g.world).append(g.state)
                        .append(g.playerCount).append(';');
                }
            }
            else
            {
                sb.append("loading");
            }
            return sb.toString();
        }
        sb.append(lobbyPoller.lastError()).append('|').append(d.game.id).append(d.game.name)
            .append(d.game.hostRsn).append(d.game.world).append(d.game.state).append('|');
        if (d.teams != null)
        {
            for (final GameDetail.Team t : d.teams)
            {
                sb.append(t.key).append(t.name).append(t.color).append(';');
            }
        }
        if (d.players != null)
        {
            for (final GameDetail.Player p : d.players)
            {
                sb.append(p.rsn).append('=').append(p.team).append(';');
            }
        }
        return sb.toString();
    }

    private void buildBrowser()
    {
        final JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
        buttons.setOpaque(false);
        buttons.add(button("Host game", this::hostGame));
        buttons.add(button("Join game", this::joinByPassphrase));
        content.add(row(buttons));
        if (!notice.isEmpty())
        {
            content.add(text(notice, ColorScheme.PROGRESS_INPROGRESS_COLOR));
        }
        addError(listPoller.lastError());

        content.add(section("Active games"));
        final List<GameSummary> list = listPoller.data();
        if (list == null)
        {
            content.add(text("Loading...", ColorScheme.LIGHT_GRAY_COLOR));
            return;
        }
        if (list.isEmpty())
        {
            content.add(text("No active games", ColorScheme.LIGHT_GRAY_COLOR));
        }
        for (final GameSummary g : list)
        {
            final JButton entry = button("<html><b>" + esc(g.name) + "</b><br>" + esc(g.hostRsn) + " · W" + g.world
                + " · " + g.state + " · " + g.playerCount + (g.playerCount == 1 ? " player" : " players")
                + "</html>", () -> joinListed(g));
            entry.setHorizontalAlignment(JButton.LEFT);
            content.add(row(entry));
        }
    }

    private void buildLobby(final GameDetail d)
    {
        final String me = rsn();
        final boolean isHost = me != null && session.isHost(me);
        final JLabel name = label(d.game.name, Color.WHITE);
        name.setFont(FontManager.getRunescapeBoldFont());
        content.add(row(name));
        content.add(text(d.game.state + " · World " + d.game.world + " · host " + d.game.hostRsn,
            ColorScheme.LIGHT_GRAY_COLOR));

        final JPanel pp = new JPanel(new BorderLayout(4, 0));
        pp.setOpaque(false);
        pp.add(label(passphrase.isEmpty() ? "Passphrase hidden" : passphrase, Color.WHITE), BorderLayout.CENTER);
        if (!passphrase.isEmpty())
        {
            pp.add(button("Copy", () -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(passphrase), null)), BorderLayout.EAST);
        }
        content.add(row(pp));
        addError(lobbyPoller.lastError());

        final List<GameDetail.Team> teams = d.teams == null ? List.of() : d.teams;
        final List<GameDetail.Player> players = d.players == null ? List.of() : d.players;
        final JPanel columns = new JPanel(new GridLayout(1, 0, 4, 0));
        columns.setOpaque(false);
        for (final GameDetail.Team t : teams)
        {
            columns.add(column(t.name, parseColor(t.color, ColorScheme.LIGHT_GRAY_COLOR), players, t.key, d, me));
        }
        content.add(row(columns));
        content.add(row(column("Unassigned", ColorScheme.LIGHT_GRAY_COLOR, players, "", d, me)));

        if (isHost)
        {
            buildHostControls(d, teams, players, me);
        }
        content.add(row(button("Leave game", this::leave)));
    }

    private JPanel column(final String name, final Color color, final List<GameDetail.Player> players,
        final String key, final GameDetail d, final String me)
    {
        final JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        col.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(3, 0, 0, 0, color), BorderFactory.createEmptyBorder(3, 4, 3, 4)));
        final JLabel header = label(name, color);
        header.setFont(FontManager.getRunescapeBoldFont());
        col.add(header);
        for (final GameDetail.Player p : players)
        {
            final String team = p.team == null ? "" : p.team;
            if (team.equals(key))
            {
                String shown = p.rsn;
                if (p.rsn.equals(d.game.hostRsn))
                {
                    shown += " (host)";
                }
                if (p.rsn.equals(me))
                {
                    shown += " (you)";
                }
                col.add(label(shown, p.rsn.equals(me) ? Color.WHITE : color));
            }
        }
        return col;
    }

    private void buildHostControls(final GameDetail d, final List<GameDetail.Team> teams,
        final List<GameDetail.Player> players, final String me)
    {
        content.add(section("Teams"));
        for (final GameDetail.Team t : teams)
        {
            final Color current = parseColor(t.color, ColorScheme.LIGHT_GRAY_COLOR);
            final JButton colour = button("", () ->
            {
                final Color picked = JColorChooser.showDialog(this, "Team colour", current);
                if (picked != null)
                {
                    host(action("team", "key", t.key, "color", toHex(picked)), null);
                }
            });
            colour.setBackground(current);
            colour.setOpaque(true);
            colour.setContentAreaFilled(false);
            colour.setToolTipText("Change team colour");
            colour.setPreferredSize(new Dimension(24, 22));
            final JTextField name = new JTextField(t.name);
            name.putClientProperty("sent", t.name);
            name.setToolTipText("Type a name and press Enter");
            name.addActionListener(e ->
            {
                final String typed = name.getText().trim();
                name.putClientProperty("sent", name.getText());
                KeyboardFocusManager.getCurrentKeyboardFocusManager().clearFocusOwner();
                if (!typed.isEmpty())
                {
                    host(action("team", "key", t.key, "name", typed), null);
                }
            });
            content.add(row(line(colour, name, null)));
        }

        content.add(section("Players"));
        final String[] options = new String[TEAM_KEYS.length];
        options[0] = "Unassigned";
        for (int i = 1; i < TEAM_KEYS.length; i++)
        {
            options[i] = plain(teamName(teams, TEAM_KEYS[i]));
        }
        for (final GameDetail.Player p : players)
        {
            final JComboBox<String> combo = new JComboBox<>(options);
            final String team = p.team == null ? "" : p.team;
            for (int i = 0; i < TEAM_KEYS.length; i++)
            {
                if (TEAM_KEYS[i].equals(team))
                {
                    combo.setSelectedIndex(i);
                }
            }
            combo.addActionListener(e -> host(action("assign", "rsn", p.rsn, "team",
                TEAM_KEYS[combo.getSelectedIndex()]), null));
            combo.setPreferredSize(new Dimension(80, 22));
            combos.add(combo);
            JButton remove = null;
            if (!p.rsn.equals(me))
            {
                remove = button("X", () ->
                {
                    if (confirm("Remove " + p.rsn + "? They can't rejoin this game."))
                    {
                        host(action("remove", "rsn", p.rsn), null);
                    }
                });
                remove.setToolTipText("Remove from game");
            }
            content.add(row(line(label(p.rsn, Color.WHITE), combo, remove)));
        }

        final JPanel buttons = new JPanel(new GridLayout(1, 0, 4, 0));
        buttons.setOpaque(false);
        buttons.add(button("Passphrase", this::changePassphrase));
        final String stateButton = hostStateButton(d.game.state);
        if (stateButton != null)
        {
            buttons.add(button(stateButton, () ->
            {
                if ("Start".equals(stateButton) || confirm("End the game for everyone?"))
                {
                    host(action(stateButton.toLowerCase()), null);
                }
            }));
        }
        content.add(row(buttons));
    }

    private static String teamName(final List<GameDetail.Team> teams, final String key)
    {
        for (final GameDetail.Team t : teams)
        {
            if (key.equals(t.key))
            {
                return t.name;
            }
        }
        return key;
    }

    private void addError(final String pollError)
    {
        final String error = actionError.isEmpty() ? pollError : actionError;
        if (error != null && !error.isEmpty())
        {
            content.add(text(error, ColorScheme.PROGRESS_ERROR_COLOR));
        }
    }

    // ---------------------------------------------------------------- small Swing helpers

    /** Left/middle(stretch)/right on one row; null slots are skipped. */
    private static JPanel line(final Component left, final Component middle, final Component right)
    {
        final JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        final GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = left instanceof JLabel ? 1 : 0;
        p.add(left, c);
        c.weightx = left instanceof JLabel ? 0 : 1;
        p.add(middle, c);
        if (right != null)
        {
            c.weightx = 0;
            c.insets = new Insets(0, 0, 0, 0);
            p.add(right, c);
        }
        return p;
    }

    /** Wraps a component so BoxLayout stretches it to the panel width with a small gap below. */
    private static JPanel row(final Component c)
    {
        final JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        p.add(c, BorderLayout.CENTER);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private static JPanel title(final String s)
    {
        final JLabel l = label(s, Color.WHITE);
        l.setFont(FontManager.getRunescapeBoldFont());
        return row(l);
    }

    private static JPanel section(final String s)
    {
        final JLabel l = label(s, ColorScheme.BRAND_ORANGE);
        l.setFont(FontManager.getRunescapeBoldFont());
        l.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        return row(l);
    }

    /** A wrapping line of plain text (escaped into HTML, width-capped to wrap in the ~225 px panel). */
    private static JPanel text(final String s, final Color color)
    {
        final JLabel l = new JLabel("<html><div style='width:190px'>" + esc(s) + "</div></html>");
        l.setForeground(color);
        l.setFont(FontManager.getRunescapeSmallFont());
        return row(l);
    }

    /** Plain-text label; server-supplied text can never switch it into HTML (see {@link #plain}). */
    private static JLabel label(final String s, final Color color)
    {
        final JLabel l = new JLabel(plain(s));
        l.setForeground(color);
        l.setFont(FontManager.getRunescapeSmallFont());
        return l;
    }

    private static JButton button(final String s, final Runnable onClick)
    {
        final JButton b = new JButton(s);
        b.setFont(FontManager.getRunescapeSmallFont());
        b.setFocusable(false);
        b.addActionListener(e -> onClick.run());
        return b;
    }

    /**
     * Swing renders any label/combo text starting with {@code <html>} as HTML (which can load
     * remote images). Names come from other players via the API, so a leading space defuses it.
     */
    static String plain(final String s)
    {
        if (s == null)
        {
            return "";
        }
        return s.regionMatches(true, 0, "<html", 0, 5) ? " " + s : s;
    }

    private static String esc(final String s)
    {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

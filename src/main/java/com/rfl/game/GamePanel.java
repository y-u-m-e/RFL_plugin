package com.rfl.game;

import static com.rfl.game.PanelWidgets.button;
import static com.rfl.game.PanelWidgets.esc;
import static com.rfl.game.PanelWidgets.label;
import static com.rfl.game.PanelWidgets.line;
import static com.rfl.game.PanelWidgets.plain;
import static com.rfl.game.PanelWidgets.row;
import static com.rfl.game.PanelWidgets.section;
import static com.rfl.game.PanelWidgets.stripes;
import static com.rfl.game.PanelWidgets.swatch;
import static com.rfl.game.PanelWidgets.text;
import static com.rfl.game.PanelWidgets.title;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;

import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public final class GamePanel extends PluginPanel
{
    static final String OPT_IN = "Turn on Enable reporting to browse and join games";
    static final String OBSERVING = "Observer mode is on: games are hidden and nothing is sent to any server. "
        + "Collisions are saved on this computer only.";
    static final String GAME_ENDED = "Game ended";
    static final String REMOVED = "You were removed from the game";
    static final String SWITCHED = "Switched account — left the game view";
    static final String ASK_HOST = "Passphrase: ask the host";
    static final String HOST_NO_PASSPHRASE = "Passphrase hidden — press Passphrase to set a new one";
    private static final String NOT_FOUND = "game not found";
    private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");
    /** The fixed referees team: not renamed or recoloured, drawn black and white striped. */
    private static final String REFEREES = "R";
    private static final String[] TEAM_KEYS = {"", "A", "B", REFEREES};

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
    /** Written on the EDT (or by {@link #dispose}), read by {@link #startPolling} from any thread. */
    private volatile boolean reporting;
    /** Set once when the plugin shuts down; nothing may restart the loop after that. */
    private volatile boolean disposed;
    /** EDT: Observer mode is on, so the Observer section replaces every game control. */
    private boolean observer;
    /** EDT: latest observer collision rows, newest first. */
    private List<String> observed = List.of();
    private Runnable openFolder = () -> { };
    /** EDT: a create/join is in flight, so Host/Join are disabled. */
    private boolean busy;
    private String notice = "";
    private String actionError = "";
    /** {@link System#nanoTime} when {@link #actionError} was set. */
    private long actionErrorAt;
    /**
     * The passphrase we set as host (create or change); the API never returns it. Empty after a
     * join — members never see it, since the host may have changed it since.
     */
    private String passphrase = "";
    /** The RSN we entered the current game as; a different logged-in RSN means an account switch. */
    private volatile String joinedRsn;
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
     * @param detail    latest lobby poll
     * @param rsn       our RSN, or null when unknown (logged out) — then roster membership isn't judged
     * @param joinedRsn the RSN we entered the game as, or null when unknown
     * @return {@link #GAME_ENDED}, {@link #SWITCHED}, {@link #REMOVED}, or null while we're still in the game
     */
    static String exitNotice(final GameDetail detail, final String rsn, final String joinedRsn)
    {
        if (detail == null || detail.game == null || "ended".equals(detail.game.state))
        {
            return GAME_ENDED;
        }
        if (rsn == null)
        {
            return null;
        }
        if (joinedRsn != null && !joinedRsn.equals(rsn))
        {
            return SWITCHED;
        }
        for (final GameDetail.Player p : roster(detail))
        {
            if (rsn.equals(p.rsn))
            {
                return null;
            }
        }
        return REMOVED;
    }

    /** @return the lobby's players, skipping entries without an RSN (the panel keys everything on it) */
    static List<GameDetail.Player> roster(final GameDetail detail)
    {
        final List<GameDetail.Player> players = new ArrayList<>();
        if (detail.players != null)
        {
            for (final GameDetail.Player p : detail.players)
            {
                if (p != null && p.rsn != null)
                {
                    players.add(p);
                }
            }
        }
        return players;
    }

    /** @return the lobby's passphrase line: only the host sees it (members' copy could be stale) */
    static String passphraseText(final boolean isHost, final String passphrase)
    {
        if (!isHost)
        {
            return ASK_HOST;
        }
        return passphrase.isEmpty() ? HOST_NO_PASSPHRASE : passphrase;
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

    /** @return whether the poll loop may (re)start: reporting on and the panel not disposed */
    static boolean mayPoll(final boolean reporting, final boolean disposed)
    {
        return reporting && !disposed;
    }

    static String toHex(final Color c)
    {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ---------------------------------------------------------------- poll loop

    /** Any thread. Restarts the loop with an immediate poll, unless reporting is off or disposed. */
    public synchronized void startPolling()
    {
        stopPolling();
        if (mayPoll(reporting, disposed))
        {
            schedule(generation, 0);
        }
    }

    /**
     * Any thread. Plugin shutdown: stops the loop for good, so a late response that would
     * restart polling (join/leave/game end) finds it disposed and does nothing.
     */
    public void dispose()
    {
        disposed = true;
        reporting = false;
        stopPolling();
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

    /**
     * One poll. Whatever happens (callback throws, or the call throws before enqueueing), the
     * next poll is scheduled exactly once, so one exception can't stop the loop for good.
     */
    private void poll(final int gen)
    {
        final long started = System.nanoTime();
        final AtomicBoolean rescheduled = new AtomicBoolean();
        final Runnable next = () ->
        {
            if (rescheduled.compareAndSet(false, true))
            {
                scheduleNext(gen);
            }
        };
        try
        {
            final String id = session.gameId();
            if (id.isEmpty())
            {
                games.list(r ->
                {
                    try
                    {
                        if (r.isOk())
                        {
                            listPoller.onSuccess(r.value());
                        }
                        else
                        {
                            listPoller.onFailure(r.error());
                        }
                        pollAnswered(started);
                    }
                    catch (final RuntimeException e)
                    {
                        log.warn("RFL game list poll failed", e);
                    }
                    finally
                    {
                        next.run();
                    }
                });
            }
            else
            {
                games.get(id, r ->
                {
                    try
                    {
                        onLobby(id, r);
                        pollAnswered(started);
                    }
                    catch (final RuntimeException e)
                    {
                        log.warn("RFL lobby poll failed", e);
                    }
                    finally
                    {
                        next.run();
                    }
                });
            }
        }
        catch (final RuntimeException e)
        {
            log.warn("RFL poll could not be sent", e);
            next.run();
        }
    }

    /**
     * Any thread. A poll started after the action error was shown retires it, so a newer poll
     * error (or recovery) shows instead of a stale "Wrong passphrase".
     */
    private void pollAnswered(final long startedNanos)
    {
        SwingUtilities.invokeLater(() ->
        {
            if (startedNanos - actionErrorAt > 0)
            {
                actionError = "";
            }
            render();
        });
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
        final String exit = exitNotice(r.value(), rsn(), joinedRsn);
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
            actionError = "";
            startPolling();
            render();
        });
    }

    /**
     * The RSN from the latest identity snapshot. Switching accounts while in a game clears the
     * game view with {@link #SWITCHED}; the old account stays on the server roster until it
     * leaves or the game times out.
     */
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

    /**
     * EDT. Observer mode: while on, the Observer section (latest collisions and Open folder)
     * replaces browsing, hosting and joining.
     *
     * @param rows       latest collision rows, newest first
     * @param openFolder opens the folder the collisions are saved in
     */
    public void setObserver(final boolean on, final List<String> rows, final Runnable openFolder)
    {
        observer = on;
        observed = rows;
        this.openFolder = openFolder;
        render();
    }

    /** EDT. Marks a create/join in flight (Host/Join disabled until {@link #entered}/{@link #fail}). */
    private void startBusy()
    {
        busy = true;
        render();
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
        startBusy();
        games.create(name.getText().trim(), pp, r -> entered(r, pp));
    }

    private void joinByPassphrase()
    {
        final String pp = askPassphrase("Join game", "");
        if (pp != null)
        {
            startBusy();
            games.joinByPassphrase(pp, r -> entered(r, ""));
        }
    }

    private void joinListed(final GameSummary game)
    {
        final String pp = askPassphrase("Join " + game.name, "");
        if (pp != null)
        {
            startBusy();
            games.join(game.id, pp, r -> entered(r.isOk() ? GameClient.Result.ok(game.id) : r, ""));
        }
    }

    /**
     * Any thread. After host/join: fetch the lobby right away and enter it.
     *
     * @param pp the passphrase when we just hosted, else {@code ""} (members never see it)
     */
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
            joinedRsn = rsn();
            lobbyPoller.onSuccess(d.value());
            session.update(d.value());
            SwingUtilities.invokeLater(() ->
            {
                passphrase = pp;
                notice = "";
                actionError = "";
                busy = false;
                startPolling();
                render();
            });
        });
    }

    private void leave()
    {
        final String id = session.gameId();
        if (id.isEmpty())
        {
            return;
        }
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
                startPolling();
                render();
            });
        });
    }

    /** EDT. Sends a host action (action + fields flat; GameClient adds installId). */
    private void host(final Map<String, Object> action, final Runnable onOk)
    {
        if (!reporting || session.gameId().isEmpty())
        {
            return;
        }
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
            actionErrorAt = System.nanoTime();
            busy = false;
            render();
        });
    }

    // ---------------------------------------------------------------- rendering (EDT)

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
        if (observer)
        {
            buildObserver();
        }
        else if (!reporting)
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
            .append(observer).append(observed).append(reporting).append(busy).append('|').append(notice).append('|').append(actionError).append('|')
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

    private void buildObserver()
    {
        content.add(section("Observer"));
        content.add(text(OBSERVING, ColorScheme.LIGHT_GRAY_COLOR));
        content.add(row(button("Open folder", openFolder)));
        if (observed.isEmpty())
        {
            content.add(text("No collisions yet", ColorScheme.LIGHT_GRAY_COLOR));
        }
        for (final String r : observed)
        {
            content.add(text(r, Color.WHITE));
        }
    }

    private void buildBrowser()
    {
        final JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
        buttons.setOpaque(false);
        buttons.add(button("Host game", this::hostGame));
        buttons.add(button("Join game", this::joinByPassphrase));
        for (final Component b : buttons.getComponents())
        {
            b.setEnabled(!busy);
        }
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
                + " · " + esc(g.state) + " · " + g.playerCount + (g.playerCount == 1 ? " player" : " players")
                + "</html>", () -> joinListed(g));
            entry.setHorizontalAlignment(JButton.LEFT);
            entry.setEnabled(!busy);
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
        pp.add(label(passphraseText(isHost, passphrase), isHost ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR),
            BorderLayout.CENTER);
        if (isHost && !passphrase.isEmpty())
        {
            pp.add(button("Copy", () -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(passphrase), null)), BorderLayout.EAST);
        }
        content.add(row(pp));
        addError(lobbyPoller.lastError());

        final List<GameDetail.Team> teams = d.teams == null ? List.of() : d.teams;
        final List<GameDetail.Player> players = roster(d);
        final JPanel columns = new JPanel(new GridLayout(1, 0, 4, 0));
        columns.setOpaque(false);
        for (final GameDetail.Team t : teams)
        {
            if (!REFEREES.equals(t.key))
            {
                final Color color = parseColor(t.color, ColorScheme.LIGHT_GRAY_COLOR);
                columns.add(column(t.name, swatch(color, 8, 8), BorderFactory.createMatteBorder(3, 0, 0, 0, color),
                    players, t.key, d, me));
            }
        }
        content.add(row(columns));
        content.add(row(column(teamName(teams, REFEREES), stripes(8, 8, true),
            BorderFactory.createMatteBorder(3, 0, 0, 0, stripes(4, 3, false)), players, REFEREES, d, me)));
        content.add(row(column("Unassigned", swatch(ColorScheme.LIGHT_GRAY_COLOR, 8, 8),
            BorderFactory.createMatteBorder(3, 0, 0, 0, ColorScheme.LIGHT_GRAY_COLOR), players, "", d, me)));

        if (isHost)
        {
            buildHostControls(d, teams, players, me);
        }
        content.add(row(button("Leave game", this::leave)));
    }

    private JPanel column(final String name, final Icon icon, final Border bar, final List<GameDetail.Player> players,
        final String key, final GameDetail d, final String me)
    {
        final JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        col.setBorder(BorderFactory.createCompoundBorder(
            bar, BorderFactory.createEmptyBorder(3, 4, 3, 4)));
        // Team colour only on the top bar and the swatch; text stays readable on the dark panel.
        final JLabel header = label(name, Color.WHITE);
        header.setIcon(icon);
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
                col.add(label(shown, p.rsn.equals(me) ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR));
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
            if (REFEREES.equals(t.key))
            {
                continue;
            }
            final Color current = parseColor(t.color, ColorScheme.LIGHT_GRAY_COLOR);
            final JButton colour = button("", () ->
            {
                final Color picked = JColorChooser.showDialog(this, "Team colour", current);
                if (picked != null)
                {
                    host(action("team", "key", t.key, "color", toHex(picked)), null);
                }
            });
            colour.setIcon(swatch(current, 14, 14));
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
        return REFEREES.equals(key) ? "Referees" : key;
    }

    private void addError(final String pollError)
    {
        final String error = actionError.isEmpty() ? pollError : actionError;
        if (error != null && !error.isEmpty())
        {
            content.add(text(error, ColorScheme.PROGRESS_ERROR_COLOR));
        }
    }
}

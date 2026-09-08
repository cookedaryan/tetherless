# Desktop UI Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish the desktop client's interface — a shared side-panel system, a light and dark theme that actually applies to every component, and the removal of every control that leads nowhere.

**Architecture:** The slide-in panel mechanism currently private to `SettingsPanel` is extracted into a `SidePanel` host that owns hosting, scrim, animation, Escape and focus. Settings, chat info and search become *content* placed in it. `Theme` gains a look-and-feel bridge that installs FlatLaf and feeds it the existing palette, so stock Swing components stop rendering in default Metal. Six dead ends are closed by wiring state that already exists to UI that already knows how to draw it.

**Tech Stack:** Java 8 source level, Swing, FlatLaf 3.4.1, JUnit 4, SQLite via `MessageRepository`, Gradle.

## Global Constraints

- **Java 8 language level.** `build.gradle:102` sets `options.release = 8` for every Java module. No `var`, no `List.of`, no switch expressions, no text blocks. Lambdas and method references are fine.
- **Checkstyle** runs on every build and fails it: `UnusedImports`, `AvoidStarImport`, `NeedBraces`. Config at `config/checkstyle/checkstyle.xml`.
- **SpotBugs with find-sec-bugs** runs at HIGH confidence and fails the build. Suppressions go in `config/spotbugs/exclude.xml`, scoped to one class or method, with a stated reason. Blanket pattern suppressions are refused.
- **No literal palette colours.** Every colour comes from `Theme`. `new Color(r, g, b, alpha)` for an alpha overlay derived from a theme colour is fine; a hardcoded RGB is not.
- **Headed tests must skip when there is no display.** Use `Assume.assumeFalse("no display on this machine", GraphicsEnvironment.isHeadless())` in a `@BeforeClass`, matching `UpdateBannerTest` and `ComposerDraftTest`. CI runs headless.
- **Tests are written first and watched fail**, per `CONTRIBUTING.md`. A step that says "run it to verify it fails" is not optional.
- **Swing rule already in force:** every UI mutation on the event dispatch thread; every crypto and I/O operation off it.
- **Verification command for the whole module:** `./gradlew :chat-desktop:check`

---

## File Structure

**Created**

| File | Responsibility |
|---|---|
| `chat-desktop/src/main/java/com/e2eechat/desktop/ui/SidePanel.java` | Hosts a panel over the window: layered pane, scrim, slide, Escape, focus return, standard header. Knows nothing about its contents. |
| `chat-desktop/src/main/java/com/e2eechat/desktop/ChatInfoPanel.java` | Content: peer identity, safety number, verification, renegotiate. |
| `chat-desktop/src/main/java/com/e2eechat/desktop/SearchPanel.java` | Content: query field and clickable results. |
| `chat-desktop/src/test/java/com/e2eechat/desktop/ThemeLookAndFeelTest.java` | The look-and-feel bridge. |
| `chat-desktop/src/test/java/com/e2eechat/desktop/SidePanelTest.java` | Panel host behaviour. Headed only. |
| `chat-desktop/src/test/java/com/e2eechat/desktop/PeerDirectoryTest.java` | Names and verification round-trip. `PeerDirectory` has no test file today. |

**Modified**

| File | Change |
|---|---|
| `ui/Theme.java` | `installLookAndFeel()`, palette pushed into `UIManager`, correct ordering in `setDark`. |
| `Main.java:25` | Install the look and feel as the first statement of `main`. |
| `SettingsPanel.java` | Loses `present()`, `Scrim` and the animation; gains a Privacy section. Becomes content. |
| `ui/MessageBubble.java:113` | Drop the `SENDING` case. |
| `ChatMessage.java` | Remove `Status.SENDING`. |
| `ui/TranscriptPanel.java` | `scrollTo(String messageId)`, plus a row index to support it. |
| `ChatWindow.java` | `openPanel`/`closePanel`; delete `showSafetyNumber` and `showChatSearch`; persist delivery status. |
| `ConversationListPanel.java` | Inline new-chat field; shield for verified peers. |
| `ChatClient.java` | Save `SENT` or `FAILED` from the transport's answer. |
| `PeerDirectory.java` | `setVerified` / `isVerified`. |
| `DesktopConfig.java` | `Preferences` rung in the precedence chain for `updates`. |
| `core-shared/.../ConnectionManager.java:319` | `sendMessage` returns `boolean`. |

**Refinement on the spec.** §6 of the spec says verification is persisted "in the same properties file" as display names. This plan uses a **second file**, `peer-verification.properties`, in the same directory with the same load/persist pattern. Reason: `peer-names.properties` carries a header saying its contents are self-asserted claims by peers, and verification is the opposite — the local user's own judgement about a peer. Mixing them would need a key prefix and would make that header untrue.

---

## Task 1: Install FlatLaf and bridge it to the palette

**Files:**
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/ui/Theme.java`
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/Main.java:25`
- Test: `chat-desktop/src/test/java/com/e2eechat/desktop/ThemeLookAndFeelTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Theme.installLookAndFeel()` — `public static void`, safe to call repeatedly, never throws.

- [ ] **Step 1: Write the failing test**

Create `chat-desktop/src/test/java/com/e2eechat/desktop/ThemeLookAndFeelTest.java`:

```java
package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.Theme;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.UIManager;

import static org.junit.Assert.assertEquals;

/**
 * The bridge between the palette and the stock Swing components.
 *
 * <p>FlatLaf was a declared dependency that nothing installed, so popup menus, scrollbars and
 * dialogs rendered in default Metal whatever the toggle said.
 */
public class ThemeLookAndFeelTest {

    private boolean originalDark;
    private String originalLookAndFeel;

    @Before
    public void remember() {
        originalDark = Theme.isDark();
        originalLookAndFeel = UIManager.getLookAndFeel().getClass().getName();
    }

    @After
    public void restore() throws Exception {
        Theme.setDark(originalDark);
        UIManager.setLookAndFeel(originalLookAndFeel);
    }

    @Test
    public void theDarkPaletteInstallsTheDarkLookAndFeel() {
        Theme.setDark(false);
        Theme.setDark(true);

        assertEquals("com.formdev.flatlaf.FlatDarkLaf",
                UIManager.getLookAndFeel().getClass().getName());
    }

    @Test
    public void theLightPaletteInstallsTheLightLookAndFeel() {
        Theme.setDark(true);
        Theme.setDark(false);

        assertEquals("com.formdev.flatlaf.FlatLightLaf",
                UIManager.getLookAndFeel().getClass().getName());
    }

    /** A popup opened next to a bubble has to be the same dark, not a neighbouring one. */
    @Test
    public void stockComponentsTakeTheirColoursFromThePalette() {
        Theme.setDark(true);
        Theme.installLookAndFeel();

        assertEquals(Theme.sidebarBg(), UIManager.getColor("PopupMenu.background"));
        assertEquals(Theme.textPrimary(), UIManager.getColor("MenuItem.foreground"));
        assertEquals(Theme.inputBg(), UIManager.getColor("TextField.background"));
    }

    @Test
    public void installingTwiceIsSafe() {
        Theme.installLookAndFeel();
        Theme.installLookAndFeel();

        assertEquals(Theme.sidebarBg(), UIManager.getColor("PopupMenu.background"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :chat-desktop:test --tests '*ThemeLookAndFeelTest*'`
Expected: FAIL — compile error, `cannot find symbol: method installLookAndFeel()`.

- [ ] **Step 3: Add the bridge to `Theme`**

In `ui/Theme.java`, add these imports beside the existing ones:

```java
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.UIManager;
```

Add after `toggle()`:

```java
    /**
     * Installs the look and feel matching the current palette, and feeds it that palette.
     *
     * <p>FlatLaf has been a declared dependency that nothing installed, so every stock component -
     * popup menus, scrollbars, tooltips, carets, dialogs - rendered in default Metal regardless of
     * the toggle. Pushing the palette in as well means a popup opened next to a bubble is the same
     * dark rather than a neighbouring one.
     *
     * <p>Never throws. A look and feel that will not load is not a reason to fail startup: the
     * custom-painted components still follow the palette and the application stays usable.
     */
    public static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(dark ? new FlatDarkLaf() : new FlatLightLaf());
        } catch (Exception e) {
            return;
        }
        applyPaletteToUiManager();
    }

    private static void applyPaletteToUiManager() {
        UIManager.put("PopupMenu.background", sidebarBg());
        UIManager.put("MenuItem.background", sidebarBg());
        UIManager.put("MenuItem.foreground", textPrimary());
        UIManager.put("MenuItem.selectionBackground", sidebarSelected());
        UIManager.put("MenuItem.selectionForeground", textPrimary());
        UIManager.put("CheckBoxMenuItem.background", sidebarBg());
        UIManager.put("CheckBoxMenuItem.foreground", textPrimary());
        UIManager.put("Separator.foreground", divider());
        UIManager.put("ScrollBar.thumb", divider());
        UIManager.put("ScrollBar.track", sidebarBg());
        UIManager.put("ToolTip.background", headerBg());
        UIManager.put("ToolTip.foreground", textPrimary());
        UIManager.put("TextField.background", inputBg());
        UIManager.put("TextField.foreground", textPrimary());
        UIManager.put("TextField.caretForeground", textPrimary());
        UIManager.put("TextField.selectionBackground", accent());
        UIManager.put("TextArea.background", inputBg());
        UIManager.put("TextArea.foreground", textPrimary());
        UIManager.put("TextArea.caretForeground", textPrimary());
        UIManager.put("TextArea.selectionBackground", accent());
        UIManager.put("PasswordField.background", inputBg());
        UIManager.put("PasswordField.foreground", textPrimary());
        UIManager.put("PasswordField.caretForeground", textPrimary());
        UIManager.put("Panel.background", sidebarBg());
        UIManager.put("OptionPane.background", sidebarBg());
        UIManager.put("OptionPane.messageForeground", textPrimary());
        UIManager.put("Component.focusColor", accent());
        UIManager.put("Component.borderColor", divider());
    }
```

Replace the body of `setDark`:

```java
    public static void setDark(boolean value) {
        if (dark == value) {
            return;
        }
        dark = value;
        PREFS.putBoolean(PREF_DARK, value);

        // Order matters. The look and feel and its palette go first, then every open window is
        // restyled, and only then do the custom-painted components repaint. Firing the listeners
        // first leaves a frame half in one theme and half in the other.
        installLookAndFeel();
        if (UIManager.getLookAndFeel() instanceof FlatLaf) {
            FlatLaf.updateUI();
        }
        for (Listener l : new ArrayList<>(LISTENERS)) {
            l.onThemeChanged();
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :chat-desktop:test --tests '*ThemeLookAndFeelTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Install it at startup**

In `Main.java`, make this the first statement of `main`, before the config directory work — the first Swing component created is `IdentityDialog`, not `ChatWindow`, so anything later is too late:

```java
    public static void main(String[] args) {
        // Before any Swing component exists. The sign-in dialog is built further down this method,
        // and a look and feel installed after a component is created does not restyle it.
        Theme.installLookAndFeel();

        String configDirPath = System.getProperty("tetherless.config.dir",
```

Add the import `import com.e2eechat.desktop.ui.Theme;`.

- [ ] **Step 6: Verify the module still builds clean**

Run: `./gradlew :chat-desktop:check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add chat-desktop/src/main/java/com/e2eechat/desktop/ui/Theme.java chat-desktop/src/main/java/com/e2eechat/desktop/Main.java chat-desktop/src/test/java/com/e2eechat/desktop/ThemeLookAndFeelTest.java
git commit -m "Install the look and feel the theme always assumed was there"
```

---

## Task 2: Extract the side-panel host

**Files:**
- Create: `chat-desktop/src/main/java/com/e2eechat/desktop/ui/SidePanel.java`
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/SettingsPanel.java`
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/ConversationListPanel.java:267-272`
- Test: `chat-desktop/src/test/java/com/e2eechat/desktop/SidePanelTest.java`

**Interfaces:**
- Consumes: `Motion.animate(int, Motion.Easing, Motion.Frame, Runnable)`, `Motion.SLOW`, `Motion.NORMAL`, `IconButton(Supplier<Icon>, String)`, `TgIcons.arrowLeft(int)`, `Theme.sidebarBg()`, `Theme.divider()`, `Theme.headerBg()`, `Theme.textPrimary()`, `Theme.font(int, float)`.
- Produces:
  - `SidePanel.Side` — enum, `LEFT` and `RIGHT`.
  - `SidePanel.ContentFactory` — `JComponent create(SidePanel panel)`.
  - `SidePanel.open(Frame frame, Side side, int width, String title, ContentFactory content)` — returns `SidePanel`, or `null` when the frame has no root pane.
  - `panel.dismiss()` — `public void`, animates out and removes itself.
  - `panel.isOpen()` — `public boolean`.

- [ ] **Step 1: Write the failing test**

Create `chat-desktop/src/test/java/com/e2eechat/desktop/SidePanelTest.java`:

```java
package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.Motion;
import com.e2eechat.desktop.ui.SidePanel;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.swing.JFrame;
import javax.swing.JLabel;
import java.awt.GraphicsEnvironment;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The panel host, on its own.
 *
 * <p>Reduced motion is switched on throughout: {@code Motion.animate} then lands on the end state
 * and runs its completion handler immediately, so opening and dismissing are synchronous and a
 * test does not have to wait on an animation.
 */
public class SidePanelTest {

    @BeforeClass
    public static void requireADisplay() {
        Assume.assumeFalse("no display on this machine", GraphicsEnvironment.isHeadless());
    }

    private JFrame frame;
    private boolean originalReducedMotion;

    @Before
    public void setUp() {
        originalReducedMotion = Motion.isReducedMotion();
        Motion.setReducedMotion(true);
        frame = new JFrame();
        // pack() gives the frame a peer so the root pane lays out; without it the layered pane
        // has zero width and every position assertion below is meaningless.
        frame.pack();
        frame.setSize(900, 600);
        frame.validate();
    }

    @After
    public void tearDown() {
        Motion.setReducedMotion(originalReducedMotion);
        frame.dispose();
    }

    private SidePanel open(SidePanel.Side side) {
        return SidePanel.open(frame, side, 420, "Test panel",
            panel -> new JLabel("body"));
    }

    @Test
    public void openingPutsThePanelInTheLayeredPane() {
        SidePanel panel = open(SidePanel.Side.LEFT);

        assertNotNull(panel);
        assertTrue(panel.isOpen());
        assertSame(frame.getRootPane().getLayeredPane(), panel.getParent());
    }

    @Test
    public void dismissingRemovesIt() {
        SidePanel panel = open(SidePanel.Side.RIGHT);

        panel.dismiss();

        assertFalse(panel.isOpen());
        assertNotNull("the frame should survive its panel", frame.getRootPane());
    }

    @Test
    public void dismissingTwiceIsHarmless() {
        SidePanel panel = open(SidePanel.Side.LEFT);

        panel.dismiss();
        panel.dismiss();

        assertFalse(panel.isOpen());
    }

    /** A right-hand panel starts off the right edge; a left-hand one off the left. */
    @Test
    public void aPanelEndsFlushAgainstItsOwnSide() {
        SidePanel left = open(SidePanel.Side.LEFT);
        assertEquals(0, left.getX());
        left.dismiss();

        SidePanel right = open(SidePanel.Side.RIGHT);
        assertEquals(frame.getRootPane().getLayeredPane().getWidth() - 420, right.getX());
    }

    @Test
    public void theContentFactoryReceivesItsHost() {
        final SidePanel[] seen = new SidePanel[1];
        SidePanel panel = SidePanel.open(frame, SidePanel.Side.LEFT, 420, "Test panel",
            host -> {
                seen[0] = host;
                return new JLabel("body");
            });

        assertSame("content must be able to dismiss its own host", panel, seen[0]);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :chat-desktop:test --tests '*SidePanelTest*'`
Expected: FAIL — compile error, `package com.e2eechat.desktop.ui does not exist` for `SidePanel`.

- [ ] **Step 3: Write `SidePanel`**

Create `chat-desktop/src/main/java/com/e2eechat/desktop/ui/SidePanel.java`:

```java
package com.e2eechat.desktop.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.RootPaneContainer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A sheet that slides in over the window from one edge.
 *
 * <p>All of this was private mechanism inside {@code SettingsPanel}, which meant settings was the
 * only thing that could ever be a panel. Nothing here knows what it contains: it owns the layered
 * pane hosting, the scrim, the slide, Escape, focus return and the header, and the content is
 * whatever the caller builds.
 *
 * <p>Added to the layered pane rather than the content pane so the split-pane layout does not have
 * to change to accommodate it, and so it can be removed cleanly.
 */
public final class SidePanel extends JPanel {

    /** Which edge the panel slides from and rests against. */
    public enum Side { LEFT, RIGHT }

    /** Builds the panel's body. Receives the host so the content can dismiss it. */
    public interface ContentFactory {
        JComponent create(SidePanel panel);
    }

    private final JLayeredPane layers;
    private final Scrim scrim;
    private final Side side;
    private final int width;

    private Component focusBeforeOpening;
    private boolean open;

    private SidePanel(JLayeredPane layers, Side side, int width, String title) {
        this.layers = layers;
        this.side = side;
        this.width = width;
        this.scrim = new Scrim();

        setLayout(new BorderLayout());
        setOpaque(true);
        setBackground(Theme.sidebarBg());
        // A hairline against the dimmed content, so the sheet has an edge rather than bleeding
        // into the scrim. It goes on whichever side faces the rest of the window.
        setBorder(side == Side.LEFT
                ? BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.divider())
                : BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.divider()));

        add(buildHeader(title), BorderLayout.NORTH);
    }

    /**
     * Slides a panel in over {@code frame}, dimming what is behind it.
     *
     * @return the panel, or {@code null} if the frame has no root pane to host it
     */
    public static SidePanel open(Frame frame, Side side, int width, String title,
                                 ContentFactory content) {
        if (!(frame instanceof RootPaneContainer)) {
            return null;
        }
        JRootPane rootPane = ((RootPaneContainer) frame).getRootPane();
        JLayeredPane layers = rootPane.getLayeredPane();

        SidePanel panel = new SidePanel(layers, side, width, title);
        panel.add(content.create(panel), BorderLayout.CENTER);
        panel.present();
        return panel;
    }

    // Named present() rather than show(): Component.show() is inherited and public, and a
    // private method with that signature will not compile.
    private void present() {
        focusBeforeOpening = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        open = true;

        Rectangle bounds = layers.getBounds();
        scrim.setBounds(0, 0, bounds.width, bounds.height);
        setBounds(offscreenX(bounds.width), 0, width, bounds.height);

        scrim.onClick(new Runnable() {
            @Override
            public void run() {
                dismiss();
            }
        });

        layers.add(scrim, JLayeredPane.MODAL_LAYER);
        layers.add(this, JLayeredPane.MODAL_LAYER);

        // Keep it filling the height, and against its edge, if the window is resized while open.
        layers.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (getParent() == layers) {
                    scrim.setBounds(0, 0, layers.getWidth(), layers.getHeight());
                    setBounds(restingX(layers.getWidth()), 0, width, layers.getHeight());
                }
            }
        });

        getInputMap(WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close-side-panel");
        getActionMap().put("close-side-panel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                dismiss();
            }
        });

        final int from = offscreenX(bounds.width);
        final int to = restingX(bounds.width);
        Motion.animate(Motion.SLOW, Motion.Easing.EASE_OUT_QUART, new Motion.Frame() {
            @Override
            public void at(float progress) {
                setLocation(Motion.lerp(from, to, progress), 0);
                scrim.setStrength(progress);
            }
        }, null);
    }

    /** Slides the panel out and removes it. Calling it twice does nothing the second time. */
    public void dismiss() {
        if (!open) {
            return;
        }
        open = false;

        final int from = getX();
        final int to = offscreenX(layers.getWidth());
        Motion.animate(Motion.NORMAL, Motion.Easing.EASE_OUT, new Motion.Frame() {
            @Override
            public void at(float progress) {
                setLocation(Motion.lerp(from, to, progress), 0);
                scrim.setStrength(1f - progress);
            }
        }, new Runnable() {
            @Override
            public void run() {
                layers.remove(SidePanel.this);
                layers.remove(scrim);
                layers.repaint();
                if (focusBeforeOpening != null) {
                    focusBeforeOpening.requestFocusInWindow();
                }
            }
        });
    }

    public boolean isOpen() {
        return open;
    }

    /** Where the panel sits when fully open. */
    private int restingX(int layerWidth) {
        return side == Side.LEFT ? 0 : layerWidth - width;
    }

    /** Where it sits when fully closed, just outside its own edge. */
    private int offscreenX(int layerWidth) {
        return side == Side.LEFT ? -width : layerWidth;
    }

    private JComponent buildHeader(String title) {
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(true);
        header.setBackground(Theme.headerBg());
        header.setBorder(BorderFactory.createEmptyBorder(14, 8, 14, 14));

        IconButton back = new IconButton(() -> TgIcons.arrowLeft(20), "Close");
        back.addActionListener(e -> dismiss());
        header.add(back, BorderLayout.WEST);

        JLabel label = new JLabel(title);
        label.setFont(Theme.font(Font.BOLD, 17f));
        label.setForeground(Theme.textPrimary());
        header.add(label, BorderLayout.CENTER);

        return header;
    }

    /** The dimmed backdrop. Clicking it closes the sheet, as a sheet should. */
    private static class Scrim extends JComponent {
        private float strength;

        Scrim() {
            setOpaque(false);
            setCursor(Cursor.getDefaultCursor());
        }

        void onClick(final Runnable action) {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    action.run();
                }
            });
        }

        void setStrength(float value) {
            strength = value;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(new Color(0, 0, 0, Math.round(110 * strength)));
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :chat-desktop:test --tests '*SidePanelTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Make `SettingsPanel` content rather than mechanism**

In `SettingsPanel.java`: delete the whole `present(...)` method and the private `Scrim` class, delete `buildHeader()` (the host provides one now), change the class declaration to `public class SettingsPanel extends JPanel`, and make the constructor public:

```java
    public SettingsPanel(ChatClient client, Runnable onClose) {
        this.client = client;
        this.onClose = onClose;

        setLayout(new BorderLayout());
        setOpaque(false);
        add(buildBody(), BorderLayout.CENTER);
    }
```

Remove the now-unused imports flagged by Checkstyle: `AlphaPanel`, `Motion`, `IconButton`, `TgIcons`, `JLayeredPane`, `JRootPane`, `KeyStroke`, `Cursor`, `Graphics`, `Rectangle`, `ComponentAdapter`, `ComponentEvent`, `KeyEvent`, `MouseAdapter`, `MouseEvent`, `Color`, `Font` — keep any still used by `buildIdentityCard` or `Row`.

- [ ] **Step 6: Open it through the host**

In `ConversationListPanel.java`, replace `openSettings()`:

```java
    private void openSettings() {
        java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(this);
        if (window instanceof java.awt.Frame) {
            SidePanel.open((java.awt.Frame) window, SidePanel.Side.LEFT, 420, "Settings",
                panel -> new SettingsPanel(client, panel::dismiss));
        }
    }
```

Add `import com.e2eechat.desktop.ui.SidePanel;`.

- [ ] **Step 7: Verify the module builds and every test passes**

Run: `./gradlew :chat-desktop:check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add chat-desktop/src/main/java/com/e2eechat/desktop/ui/SidePanel.java chat-desktop/src/main/java/com/e2eechat/desktop/SettingsPanel.java chat-desktop/src/main/java/com/e2eechat/desktop/ConversationListPanel.java chat-desktop/src/test/java/com/e2eechat/desktop/SidePanelTest.java
git commit -m "Extract the panel host so settings is not the only thing that can be one"
```

---

## Task 3: Privacy section and the update-check preference

**Files:**
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/DesktopConfig.java`
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/SettingsPanel.java`
- Modify: `chat-desktop/src/test/java/com/e2eechat/desktop/DesktopConfigTest.java`

**Interfaces:**
- Consumes: `DesktopConfig.UPDATES_KEY`, `UpdateChecker.ENABLED_PROPERTY`.
- Produces: `DesktopConfig.setUpdateChecksPreference(boolean)` and `DesktopConfig.updateChecksPreference()` — both `public static`, backed by `Preferences`.

- [ ] **Step 1: Write the failing test**

Append to `chat-desktop/src/test/java/com/e2eechat/desktop/DesktopConfigTest.java`:

```java
    /**
     * The toggle writes to Preferences, which sits one rung above the built-in default: a
     * deployment that sets updates=false keeps it off and the toggle cannot override it.
     */
    @Test
    public void anExplicitSettingBeatsThePreference() throws Exception {
        DesktopConfig.setUpdateChecksPreference(true);
        System.setProperty(UpdateChecker.ENABLED_PROPERTY, "false");
        try {
            assertFalse(DesktopConfig.load(tmp.getRoot(), new String[0]).updateChecks());
        } finally {
            System.clearProperty(UpdateChecker.ENABLED_PROPERTY);
        }
    }

    @Test
    public void thePreferenceDecidesWhenNothingElseIsConfigured() {
        DesktopConfig.setUpdateChecksPreference(false);
        assertFalse(DesktopConfig.load(tmp.getRoot(), new String[0]).updateChecks());

        DesktopConfig.setUpdateChecksPreference(true);
        assertTrue(DesktopConfig.load(tmp.getRoot(), new String[0]).updateChecks());
    }

    @Test
    public void updateChecksAreOnWhenNothingHasEverBeenSet() {
        DesktopConfig.clearUpdateChecksPreference();
        assertTrue(DesktopConfig.load(tmp.getRoot(), new String[0]).updateChecks());
    }
```

Restore the preference in the existing `@After` so the tests do not leak into the developer's own settings:

```java
        DesktopConfig.clearUpdateChecksPreference();
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :chat-desktop:test --tests '*DesktopConfigTest*'`
Expected: FAIL — compile error, `cannot find symbol: method setUpdateChecksPreference(boolean)`.

- [ ] **Step 3: Add the preference rung to `DesktopConfig`**

Add the import `import java.util.prefs.Preferences;` and:

```java
    /** Where the settings toggle stores its answer. Mirrors how Theme stores dark mode. */
    private static final Preferences PREFS = Preferences.userRoot().node("com/e2eechat/desktop");

    private static final String PREF_UPDATES = "updateChecks";

    /** Records the user's choice. Configuration set by a deployment still wins over this. */
    public static void setUpdateChecksPreference(boolean enabled) {
        PREFS.putBoolean(PREF_UPDATES, enabled);
    }

    /** The stored choice, defaulting to on. */
    public static boolean updateChecksPreference() {
        return PREFS.getBoolean(PREF_UPDATES, true);
    }

    /** Forgets the stored choice, so the built-in default applies again. Used by tests. */
    public static void clearUpdateChecksPreference() {
        PREFS.remove(PREF_UPDATES);
    }
```

In `load(...)`, replace the `updates` resolution:

```java
        // Precedence, lowest last: arguments, system properties, config.properties, the settings
        // toggle, then the built-in default. The toggle sits below configuration deliberately - a
        // deployment that mandates updates=false must not be overridable from the settings sheet.
        boolean updates = !"false".equalsIgnoreCase(firstNonEmpty(
                System.getProperty(UpdateChecker.ENABLED_PROPERTY),
                file.getProperty(UPDATES_KEY),
                String.valueOf(updateChecksPreference())));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :chat-desktop:test --tests '*DesktopConfigTest*'`
Expected: PASS.

- [ ] **Step 5: Add the Privacy section**

In `SettingsPanel.buildBody()`, insert between the Appearance and Connection sections:

```java
        body.add(sectionTitle("Privacy"));
        body.add(new Row(TgIcons.info(19), "Check for updates on startup",
                "Asks GitHub whether a newer version exists. GitHub, and anyone watching the "
                        + "network, learns this address runs Tetherless and roughly when it "
                        + "started.",
                toggle(DesktopConfig.updateChecksPreference(), value -> {
                    DesktopConfig.setUpdateChecksPreference(value);
                    System.setProperty(UpdateChecker.ENABLED_PROPERTY, String.valueOf(value));
                })));
```

Keep the `TgIcons` import if Step 5 of Task 2 removed it.

- [ ] **Step 6: Verify**

Run: `./gradlew :chat-desktop:check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add chat-desktop/src/main/java/com/e2eechat/desktop/DesktopConfig.java chat-desktop/src/main/java/com/e2eechat/desktop/SettingsPanel.java chat-desktop/src/test/java/com/e2eechat/desktop/DesktopConfigTest.java
git commit -m "Give the update check a control instead of a config file"
```

---

## Task 4: Make a failed send visible

**Files:**
- Modify: `core-shared/src/main/java/com/e2eechat/core/network/ConnectionManager.java:319`
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java`
- Test: `chat-desktop/src/test/java/com/e2eechat/desktop/ChatClientTest.java`

**Interfaces:**
- Consumes: `ChatMessage.Status.FAILED`, the recording repository already in `ChatClientTest`.
- Produces: `ConnectionManager.sendMessage(Message)` now returns `boolean` — true when the frame was queued, false when it was dropped because the connection is not up.

- [ ] **Step 1: Write the failing test**

Add to `ChatClientTest`, and add a `savedStatuses` list beside `aliceSaved` recording `status.name()` in the recording repository's ten-argument `saveMessage`:

```java
    /**
     * A send the transport refuses is recorded as failed, so the bubble can say so.
     *
     * <p>MessageBubble has always drawn a red tick for FAILED and nothing ever set it: a message
     * that never left the machine looked exactly like one that did.
     */
    @Test
    public void aSendTheTransportRefusesIsRecordedAsFailed() throws Exception {
        aliceClient.startSecureChat(bobId);
        refuseAliceTransport();
        aliceSaved.clear();
        savedStatuses.clear();

        String messageId = aliceClient.sendMessage("this one does not leave", null);

        assertNotNull("the message should still get an id and a bubble", messageId);
        assertEquals(1, savedStatuses.size());
        assertEquals("FAILED", savedStatuses.get(0));
    }

    @Test
    public void aSendTheTransportAcceptsIsRecordedAsSent() {
        aliceClient.startSecureChat(bobId);
        savedStatuses.clear();

        assertNotNull(aliceClient.sendMessage("this one leaves", null));

        assertEquals(1, savedStatuses.size());
        assertEquals("SENT", savedStatuses.get(0));
    }

    /** Swaps Alice's transport for one that refuses everything, as a closed connection would. */
    private void refuseAliceTransport() throws Exception {
        ConnectionManager refusing = new ConnectionManager("localhost", 0, "unused", aliceClient) {
            @Override
            public boolean sendMessage(Message message) {
                return false;
            }

            @Override
            public void start() {
                // No socket: this fixture is the network.
            }
        };
        Field field = ChatClient.class.getDeclaredField("connectionManager");
        field.setAccessible(true);
        field.set(aliceClient, refusing);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :chat-desktop:test --tests '*ChatClientTest*'`
Expected: FAIL — compile error, `sendMessage(Message) in <anonymous> cannot override; return type boolean is not compatible with void`.

- [ ] **Step 3: Let the transport answer**

In `ConnectionManager.java`, replace `sendMessage`:

```java
    /**
     * Queues a frame for the relay.
     *
     * @return true when it was queued, false when there is no connection to put it on. The caller
     *         needs to know: a message that was dropped here and reported as sent is the
     *         difference between a red tick and a lie.
     */
    public boolean sendMessage(Message msg) {
        if (state == ConnectionState.CONNECTED) {
            return outboundQueue.offer(msg);
        }
        logger.warn("Cannot send message, state is {}", state);
        return false;
    }
```

- [ ] **Step 4: Record what actually happened**

In `ChatClient.java`, change `transmit` and the save in `sendMessage`:

```java
    /** Hands a signed frame to the relay. False when there was no connection to take it. */
    private boolean transmit(Message message) {
        ConnectionManager connection = connectionManager;
        return connection != null && connection.sendMessage(message);
    }
```

`SecureChat.Transport` expects `void send(Message)`, so keep the method reference it was constructed with by adding an adapter in the constructor — replace `this::transmit` with `message -> transmit(message)`.

In `sendMessage`, replace the save and transmit:

```java
            Message encrypted = secureChat.encrypt(peerId, messageId, body);

            boolean accepted = transmit(encrypted);
            messageRepository.saveMessage(messageId, clientId, peerId, text, timestamp,
                    accepted ? ChatMessage.Status.SENT : ChatMessage.Status.FAILED,
                    replyId, replySender, replyPreview, true);
            return messageId;
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :chat-desktop:test --tests '*ChatClientTest*'`
Expected: PASS.

- [ ] **Step 6: Verify nothing in core-shared or the harness regressed**

Run: `./gradlew :core-shared:check :chat-desktop:check :chat-desktop:integTest`
Expected: BUILD SUCCESSFUL. `ConnectionManagerTest.sendWhileDisconnectedIsDropped` still compiles — it ignores the return value.

- [ ] **Step 7: Commit**

```bash
git add core-shared/src/main/java/com/e2eechat/core/network/ConnectionManager.java chat-desktop/src/main/java/com/e2eechat/desktop/ChatClient.java chat-desktop/src/test/java/com/e2eechat/desktop/ChatClientTest.java
git commit -m "Let the transport say whether a message actually went"
```

---

## Task 5: Persist delivery state

**Files:**
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/ChatWindow.java:271-275`
- Test: `chat-desktop/src/test/java/com/e2eechat/desktop/MessageRepositoryTest.java`

**Interfaces:**
- Consumes: `MessageRepository.updateStatus(String, ChatMessage.Status)` — exists, tested, never called.
- Produces: nothing new.

- [ ] **Step 1: Write the failing test**

Add to `MessageRepositoryTest`:

```java
    /**
     * A double tick has to survive a restart. The transcript updates the bubble in memory when an
     * acknowledgement arrives; unless the row is advanced too, reopening the app reverts every
     * delivered message to a single tick.
     */
    @Test
    public void anAdvancedStatusIsStillThereAfterReopeningTheDatabase() {
        repository.saveMessage("m1", ALICE, BOB, "delivered later", 1,
                ChatMessage.Status.SENT, null, null, null, true);

        repository.updateStatus("m1", ChatMessage.Status.DELIVERED);

        MessageRepository reopened = new MessageRepository(dbPath, dbKey);
        assertEquals(ChatMessage.Status.DELIVERED,
                reopened.getMessages(ALICE, BOB, 10).get(0).getStatus());
    }
```

- [ ] **Step 2: Run test to verify it fails or passes**

Run: `./gradlew :chat-desktop:test --tests '*MessageRepositoryTest*'`
Expected: PASS. `updateStatus` already works — this test pins the repository half so the next step can be about the caller. If it fails, stop: the repository is broken and that is a different bug.

- [ ] **Step 3: Call it from the acknowledgement path**

In `ChatWindow.java`, where `DELIVERY_ACK` is handled around line 271, add the repository write beside the in-memory one:

```java
                    transcript.updateStatus(ackedId, ChatMessage.Status.DELIVERED);
                    // The bubble alone is not enough: without this the tick is gone on restart.
                    client.getMessageRepository()
                            .updateStatus(ackedId, ChatMessage.Status.DELIVERED);
```

- [ ] **Step 4: Verify**

Run: `./gradlew :chat-desktop:check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add chat-desktop/src/main/java/com/e2eechat/desktop/ChatWindow.java chat-desktop/src/test/java/com/e2eechat/desktop/MessageRepositoryTest.java
git commit -m "Keep the delivery tick after a restart"
```

---

## Task 6: Remove the unreachable SENDING state

**Files:**
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/ChatMessage.java`
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/ui/MessageBubble.java:113-114`
- Test: `chat-desktop/src/test/java/com/e2eechat/desktop/MessageRepositoryTest.java`

**Interfaces:**
- Consumes: `MessageRepository.readRow`, which already falls back to `SENT` on an unknown status name.
- Produces: `ChatMessage.Status` with four constants — `SENT`, `DELIVERED`, `READ`, `FAILED`.

- [ ] **Step 1: Write the failing test**

Add to `MessageRepositoryTest`:

```java
    /**
     * Rows written before SENDING was removed must still open.
     *
     * <p>readRow already catches IllegalArgumentException from Status.valueOf and falls back to
     * SENT. This pins that behaviour against the constant that no longer exists, because the
     * fallback is the only reason removing it is safe.
     */
    @Test
    public void aRowStoredWithARetiredStatusStillReads() throws Exception {
        try (PreparedStatement pstmt = keepAlive.prepareStatement(
                "INSERT INTO messages (message_id, sender, receiver, content, timestamp, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            pstmt.setString(1, "legacy-1");
            pstmt.setString(2, ALICE);
            pstmt.setString(3, BOB);
            pstmt.setString(4, "written when SENDING existed");
            pstmt.setLong(5, 1L);
            pstmt.setString(6, "SENDING");
            pstmt.executeUpdate();
        }

        List<ChatMessage> messages = repository.getMessages(ALICE, BOB, 10);

        assertEquals(1, messages.size());
        assertEquals(ChatMessage.Status.SENT, messages.get(0).getStatus());
    }
```

Note the content is stored unencrypted here on purpose; `decrypt` returns non-Base64 input unchanged, which an existing test already covers.

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :chat-desktop:test --tests '*MessageRepositoryTest*'`
Expected: PASS. This is a safety net established *before* the removal, not a red test.

- [ ] **Step 3: Remove the constant**

In `ChatMessage.java`, delete from the `Status` enum:

```java
        /** Queued locally; the relay has not seen it. Rendered as a clock. */
        SENDING,
```

and add to the enum's Javadoc:

```java
    /**
     * Delivery progress, mirrored by the tick glyph in the corner of an outgoing bubble.
     *
     * <p>There is deliberately no "sending" state. It would mean "queued locally, the relay has
     * not seen it", and this protocol has no such moment: handing a frame to the transport is
     * immediate, and the only later confirmation is DELIVERY_ACK, which comes from the peer rather
     * than the relay. It becomes meaningful the day an offline send queue exists.
     */
```

- [ ] **Step 4: Remove the glyph**

In `MessageBubble.statusIcon()`, delete:

```java
            case SENDING:
                return TgIcons.clock(13);
```

Leave `TgIcons.clock` in place — it is a general-purpose icon and costs nothing.

- [ ] **Step 5: Run the whole module**

Run: `./gradlew :chat-desktop:check`
Expected: BUILD SUCCESSFUL. Any remaining reference to `Status.SENDING` is a compile error and must be removed.

- [ ] **Step 6: Commit**

```bash
git add chat-desktop/src/main/java/com/e2eechat/desktop/ChatMessage.java chat-desktop/src/main/java/com/e2eechat/desktop/ui/MessageBubble.java chat-desktop/src/test/java/com/e2eechat/desktop/MessageRepositoryTest.java
git commit -m "Remove a message state the protocol cannot produce"
```

---

## Task 7: Persist peer verification

**Files:**
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/PeerDirectory.java`
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/ConversationListPanel.java`
- Test: `chat-desktop/src/test/java/com/e2eechat/desktop/PeerDirectoryTest.java`

**Interfaces:**
- Consumes: `PeerDirectory(File configDir)`.
- Produces: `setVerified(String peerId, boolean verified)` — `public synchronized void`; `isVerified(String peerId)` — `public boolean`.

- [ ] **Step 1: Write the failing test**

Create `chat-desktop/src/test/java/com/e2eechat/desktop/PeerDirectoryTest.java`:

```java
package com.e2eechat.desktop;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Peer names and verification.
 *
 * <p>Verification is kept in its own file. peer-names.properties carries a header saying its
 * contents are claims made by peers about themselves; verification is the opposite - the local
 * user's own judgement - and putting the two in one file would make that header untrue.
 */
public class PeerDirectoryTest {

    private static final String PEER = "4f3a91c28b7e05d6a1b2c3d4e5f60718";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File home;

    @Before
    public void setUp() throws Exception {
        home = tmp.newFolder("profile");
    }

    @Test
    public void aPeerIsNotVerifiedUntilSomebodySaysSo() {
        assertFalse(new PeerDirectory(home).isVerified(PEER));
    }

    @Test
    public void verificationSurvivesARestart() {
        new PeerDirectory(home).setVerified(PEER, true);

        assertTrue(new PeerDirectory(home).isVerified(PEER));
    }

    @Test
    public void verificationCanBeWithdrawn() {
        PeerDirectory directory = new PeerDirectory(home);
        directory.setVerified(PEER, true);

        directory.setVerified(PEER, false);

        assertFalse(directory.isVerified(PEER));
        assertFalse(new PeerDirectory(home).isVerified(PEER));
    }

    /** Verifying one peer says nothing about another. */
    @Test
    public void verificationIsPerPeer() {
        PeerDirectory directory = new PeerDirectory(home);
        directory.setVerified(PEER, true);

        assertFalse(directory.isVerified("0000000000000000000000000000dead"));
    }

    @Test
    public void namesAndVerificationDoNotDisturbEachOther() {
        PeerDirectory directory = new PeerDirectory(home);

        directory.setName(PEER, "Bob");
        directory.setVerified(PEER, true);

        PeerDirectory reopened = new PeerDirectory(home);
        assertEquals("Bob", reopened.nameFor(PEER));
        assertTrue(reopened.isVerified(PEER));
    }

    @Test
    public void anUnknownPeerFallsBackToTheShortFormOfItsId() {
        assertEquals(PEER.substring(0, 8), new PeerDirectory(home).nameFor(PEER));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :chat-desktop:test --tests '*PeerDirectoryTest*'`
Expected: FAIL — compile error, `cannot find symbol: method isVerified(String)`.

- [ ] **Step 3: Add verification to `PeerDirectory`**

Add beside the existing name field and constants:

```java
    private static final String VERIFIED_FILE_NAME = "peer-verification.properties";

    private final File verifiedFile;
    private final Set<String> verified = ConcurrentHashMap.newKeySet();
```

In the constructor, after `load()`:

```java
        this.verifiedFile = new File(configDir, VERIFIED_FILE_NAME);
        loadVerified();
```

`verifiedFile` must be assigned before `loadVerified()`, so declare it above and set both fields before either load call. Then:

```java
    private void loadVerified() {
        if (!verifiedFile.exists()) {
            return;
        }
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(verifiedFile)) {
            props.load(in);
        } catch (Exception e) {
            logger.warn("Could not read {}", verifiedFile, e);
            return;
        }
        for (String key : props.stringPropertyNames()) {
            if (Boolean.parseBoolean(props.getProperty(key))) {
                verified.add(key);
            }
        }
    }

    /**
     * Records that the local user has compared safety numbers with this peer and they matched.
     *
     * <p>This is the user's own judgement, not anything the peer asserted, which is why it lives in
     * its own file rather than beside the names peers claim for themselves.
     */
    public synchronized void setVerified(String peerId, boolean isVerified) {
        if (peerId == null) {
            return;
        }
        boolean changed = isVerified ? verified.add(peerId) : verified.remove(peerId);
        if (changed) {
            persistVerified();
        }
    }

    /** True when the user has marked this peer verified. */
    public boolean isVerified(String peerId) {
        return peerId != null && verified.contains(peerId);
    }

    private void persistVerified() {
        Properties props = new Properties();
        for (String peerId : verified) {
            props.setProperty(peerId, "true");
        }
        try (FileOutputStream out = new FileOutputStream(verifiedFile)) {
            props.store(out, "Peers whose safety number this user has compared and accepted.");
        } catch (Exception e) {
            logger.error("Could not persist peer verification to {}", verifiedFile, e);
        }
    }
```

Add the imports `java.util.Set` and `java.util.concurrent.ConcurrentHashMap` if not present.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :chat-desktop:test --tests '*PeerDirectoryTest*'`
Expected: PASS, 6 tests.

- [ ] **Step 5: Make the sidebar read it**

In `ConversationListPanel.java`, where each `Conversation` is built from the repository, set the flag from the directory instead of copying a value nothing sets. Find the loop that builds rows and add:

```java
            conversation.setVerified(client.getPeerDirectory().isVerified(conversation.getPeerId()));
```

Remove the line at 408 that copies `existing.isVerified()` — the directory is now the source of
truth.

Then draw it. In the row painter, beside where the display name is drawn, add the shield after the
name so a verified peer is visible without opening anything:

```java
            if (conversation.isVerified()) {
                Icon shield = TgIcons.tinted(TgIcons.shield(13), Theme.accent());
                shield.paintIcon(this, g2, nameX + nameWidth + 4,
                        nameBaseline - shield.getIconHeight() + 2);
            }
```

`nameX`, `nameWidth` and `nameBaseline` are whatever the surrounding painter already uses for the
name; take the existing locals rather than recomputing them.

- [ ] **Step 6: Verify**

Run: `./gradlew :chat-desktop:check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add chat-desktop/src/main/java/com/e2eechat/desktop/PeerDirectory.java chat-desktop/src/main/java/com/e2eechat/desktop/ConversationListPanel.java chat-desktop/src/test/java/com/e2eechat/desktop/PeerDirectoryTest.java
git commit -m "Let a peer actually be marked verified"
```

---

## Task 8: The chat info panel

**Files:**
- Create: `chat-desktop/src/main/java/com/e2eechat/desktop/ChatInfoPanel.java`
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/ChatWindow.java`
- Test: `chat-desktop/src/test/java/com/e2eechat/desktop/ChatInfoPanelTest.java`

**Interfaces:**
- Consumes: `SidePanel.open(...)`, `PeerDirectory.setVerified/isVerified`, `ChatClient.getPeerFingerprint(String)`, `ChatClient.getClientId()`, `ChatClient.restartSecureChat(String)`.
- Produces: `ChatInfoPanel(ChatClient client, String peerId, String ownFingerprint, Runnable onSearchRequested)` — a `JPanel`; `ChatInfoPanel.groupFingerprint(String)` — `static String`, the fingerprint split into readable groups.

- [ ] **Step 1: Write the failing test**

Create `chat-desktop/src/test/java/com/e2eechat/desktop/ChatInfoPanelTest.java`:

```java
package com.e2eechat.desktop;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The formatting of a safety number.
 *
 * <p>Headless: this is the part that has to be right, and it is pure text. Two people read these
 * numbers aloud to each other a group at a time, which a single unbroken run of hex defeats.
 */
public class ChatInfoPanelTest {

    @Test
    public void aFingerprintIsSplitIntoReadableGroups() {
        String grouped = ChatInfoPanel.groupFingerprint("AABBCCDDEEFF00112233445566778899");

        assertEquals("AABBC CDDEE FF001 12233 44556 67788 99", grouped);
    }

    @Test
    public void aMissingFingerprintSaysSoRatherThanShowingNothing() {
        assertTrue(ChatInfoPanel.groupFingerprint(null).length() > 0);
        assertEquals("(no key received yet)", ChatInfoPanel.groupFingerprint(null));
    }

    @Test
    public void groupingLeavesNoCharacterBehind() {
        String source = "0123456789ABCDEF0123456789ABCDEF";

        String grouped = ChatInfoPanel.groupFingerprint(source);

        assertEquals(source, grouped.replace(" ", ""));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :chat-desktop:test --tests '*ChatInfoPanelTest*'`
Expected: FAIL — compile error, `cannot find symbol: class ChatInfoPanel`.

- [ ] **Step 3: Write `ChatInfoPanel`**

Create `chat-desktop/src/main/java/com/e2eechat/desktop/ChatInfoPanel.java`. The grouping method carries the behaviour under test:

```java
    /** Splits a fingerprint into five-character groups, which is how two people read it aloud. */
    static String groupFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.trim().isEmpty()) {
            return "(no key received yet)";
        }
        String compact = fingerprint.replace(":", "").replace(" ", "");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < compact.length(); i++) {
            if (i > 0 && i % 5 == 0) {
                out.append(' ');
            }
            out.append(compact.charAt(i));
        }
        return out.toString();
    }
```

The class around it:

```java
package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.PillButton;
import com.e2eechat.desktop.ui.Theme;
import com.e2eechat.desktop.ui.ToggleSwitch;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;

/**
 * Everything about one conversation, and the safety number in a shape worth comparing.
 *
 * <p>This was a JOptionPane holding a newline-joined string. Two people read a safety number aloud
 * to each other a group at a time; a paragraph of hex defeats that, and it is the only check
 * either of them has against a machine-in-the-middle.
 */
public class ChatInfoPanel extends JPanel {

    /** Monospaced so the groups line up vertically between the two blocks. */
    private static final String MONOSPACED = Font.MONOSPACED;

    private final ChatClient client;
    private final String peerId;

    public ChatInfoPanel(ChatClient client, String peerId, String ownFingerprint,
                         Runnable onSearchRequested) {
        this.client = client;
        this.peerId = peerId;

        setOpaque(false);
        setLayout(new BorderLayout());

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(8, 16, 24, 16));

        body.add(identityBlock());
        body.add(fingerprintBlock("You", ownFingerprint));
        body.add(fingerprintBlock(client.displayNameFor(peerId),
                client.getPeerFingerprint(peerId)));
        body.add(verifiedRow());
        body.add(action("Renegotiate encryption", () -> client.restartSecureChat(peerId)));
        body.add(action("Search this conversation", onSearchRequested));

        add(body, BorderLayout.NORTH);
    }

    /** Name, id, and a copy button — the id is the only thing another person needs. */
    private JComponent identityBlock() {
        JPanel block = new JPanel(new BorderLayout());
        block.setOpaque(false);

        JLabel name = new JLabel(client.displayNameFor(peerId));
        name.setFont(Theme.font(Font.BOLD, 16f));
        name.setForeground(Theme.textPrimary());

        JLabel id = new JLabel(peerId);
        id.setFont(new Font(MONOSPACED, Font.PLAIN, 12));
        id.setForeground(Theme.textSecondary());

        PillButton copy = new PillButton("Copy id");
        copy.addActionListener(e -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(peerId), null));

        block.add(name, BorderLayout.NORTH);
        block.add(id, BorderLayout.CENTER);
        block.add(copy, BorderLayout.EAST);
        return block;
    }

    private JComponent fingerprintBlock(String label, String fingerprint) {
        JPanel block = new JPanel(new BorderLayout());
        block.setOpaque(false);
        block.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));

        JLabel caption = new JLabel(label);
        caption.setFont(Theme.font(Font.PLAIN, 12f));
        caption.setForeground(Theme.textSecondary());

        JLabel digits = new JLabel(groupFingerprint(fingerprint));
        digits.setFont(new Font(MONOSPACED, Font.PLAIN, 13));
        digits.setForeground(Theme.textPrimary());

        block.add(caption, BorderLayout.NORTH);
        block.add(digits, BorderLayout.CENTER);
        return block;
    }

    private JComponent verifiedRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(16, 0, 0, 0));

        JLabel label = new JLabel("Verified");
        label.setFont(Theme.font(Font.PLAIN, 14f));
        label.setForeground(Theme.textPrimary());

        ToggleSwitch toggle = new ToggleSwitch(client.getPeerDirectory().isVerified(peerId));
        toggle.setOnChange(value -> client.getPeerDirectory().setVerified(peerId, value));

        row.add(label, BorderLayout.WEST);
        row.add(toggle, BorderLayout.EAST);
        return row;
    }

    private JComponent action(String label, Runnable onClick) {
        PillButton button = new PillButton(label);
        button.addActionListener(e -> onClick.run());
        return button;
    }
```

then `groupFingerprint` as written above, and a closing brace.

**Check `ToggleSwitch` and `PillButton` constructor signatures before writing this** — read
`ui/ToggleSwitch.java` and `ui/PillButton.java` and match what `SettingsPanel.toggle(...)` already
does rather than assuming the shapes above. If they differ, follow the existing usage; the
structure of this class does not change.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :chat-desktop:test --tests '*ChatInfoPanelTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Open it from the header, and delete the dialog**

In `ChatWindow.java`, add panel ownership:

```java
    /** The panel currently open over the window, if any. Only one at a time. */
    private SidePanel openPanel;

    private void openPanel(SidePanel.Side side, String title, SidePanel.ContentFactory content) {
        closePanel();
        openPanel = SidePanel.open(this, side, 380, title, content);
    }

    private void closePanel() {
        if (openPanel != null) {
            openPanel.dismiss();
            openPanel = null;
        }
    }
```

Replace the `safety` button's action:

```java
            IconButton safety = new IconButton(() -> TgIcons.shield(20), "Chat info");
            safety.addActionListener(e -> showChatInfo());
```

and add:

```java
    private void showChatInfo() {
        String peerId = client.getReceiverId();
        if (peerId == null) {
            return;
        }
        openPanel(SidePanel.Side.RIGHT, "Chat info",
            panel -> new ChatInfoPanel(client, peerId, ownFingerprint, () -> {
                panel.dismiss();
                showChatSearch();
            }));
    }
```

Delete `showSafetyNumber()` entirely, along with the `group(...)` helper it used if nothing else calls it. Update the "Safety number…" item in `showChatMenu` to call `showChatInfo()`.

- [ ] **Step 6: Verify**

Run: `./gradlew :chat-desktop:check`
Expected: BUILD SUCCESSFUL. `JOptionPane` should no longer appear in `ChatWindow` except in the search path, which Task 9 removes.

- [ ] **Step 7: Commit**

```bash
git add chat-desktop/src/main/java/com/e2eechat/desktop/ChatInfoPanel.java chat-desktop/src/main/java/com/e2eechat/desktop/ChatWindow.java chat-desktop/src/test/java/com/e2eechat/desktop/ChatInfoPanelTest.java
git commit -m "Give the safety number a surface worth comparing on"
```

---

## Task 9: The search panel

**Files:**
- Create: `chat-desktop/src/main/java/com/e2eechat/desktop/SearchPanel.java`
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/ui/TranscriptPanel.java`
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/ChatWindow.java`
- Test: `chat-desktop/src/test/java/com/e2eechat/desktop/TranscriptScrollToTest.java`

**Interfaces:**
- Consumes: `MessageRepository.searchMessages(String, String, int)`, `SidePanel`.
- Produces: `TranscriptPanel.scrollTo(String messageId)` — `public boolean`, true when a row with that id was found and scrolled to; `SearchPanel(ChatClient client, String peerId, java.util.function.Consumer<ChatMessage> onSelect)`.

- [ ] **Step 1: Write the failing test**

Create `chat-desktop/src/test/java/com/e2eechat/desktop/TranscriptScrollToTest.java`:

```java
package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.TranscriptPanel;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Jumping to a message by id.
 *
 * <p>This is what makes a search result something a person can act on rather than something they
 * can only read.
 */
public class TranscriptScrollToTest {

    @BeforeClass
    public static void requireADisplay() {
        Assume.assumeFalse("no display on this machine", GraphicsEnvironment.isHeadless());
    }

    private static TranscriptPanel withHistory(int count) {
        TranscriptPanel transcript = new TranscriptPanel("alice");
        transcript.setSize(600, 400);
        List<ChatMessage> history = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            history.add(new ChatMessage("m" + i, "alice", "bob", "message " + i,
                    1000L + i, ChatMessage.Status.SENT));
        }
        transcript.setHistory(history);
        return transcript;
    }

    @Test
    public void aKnownMessageIsFound() {
        TranscriptPanel transcript = withHistory(40);

        assertTrue(transcript.scrollTo("m7"));
    }

    @Test
    public void anUnknownMessageIsReportedRatherThanIgnored() {
        TranscriptPanel transcript = withHistory(40);

        assertFalse(transcript.scrollTo("no-such-message"));
        assertFalse(transcript.scrollTo(null));
    }

    @Test
    public void replacingTheHistoryForgetsTheOldRows() {
        TranscriptPanel transcript = withHistory(10);
        assertTrue(transcript.scrollTo("m3"));

        transcript.setHistory(new ArrayList<ChatMessage>());

        assertFalse("rows from the previous conversation should be gone",
                transcript.scrollTo("m3"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :chat-desktop:test --tests '*TranscriptScrollToTest*'`
Expected: FAIL — compile error, `cannot find symbol: method scrollTo(String)`.

- [ ] **Step 3: Index the rows and scroll to one**

In `TranscriptPanel.java`, add beside the existing `messages` list:

```java
    /** Row component for each message id, so a search hit can be scrolled to. */
    private final Map<String, Component> rowsById = new HashMap<>();
```

Clear it in `setHistory` beside `column.removeAll()`:

```java
        rowsById.clear();
```

Record it at the end of `appendInternal`, before `return row`:

```java
        if (message.getMessageId() != null) {
            rowsById.put(message.getMessageId(), row);
        }
```

Add:

```java
    /**
     * Brings the message with {@code messageId} into view.
     *
     * @return false when no row carries that id - messages stored before ids were recorded have
     *         none, and a caller should say so rather than appear to do nothing
     */
    public boolean scrollTo(String messageId) {
        if (messageId == null) {
            return false;
        }
        Component row = rowsById.get(messageId);
        if (row == null) {
            return false;
        }
        Rectangle bounds = row.getBounds();
        column.scrollRectToVisible(new Rectangle(0, Math.max(0, bounds.y - 40),
                bounds.width, bounds.height + 80));
        return true;
    }
```

Add the imports `java.util.HashMap`, `java.util.Map`, and `java.awt.Rectangle` if absent.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :chat-desktop:test --tests '*TranscriptScrollToTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Write `SearchPanel` and wire it**

Create `SearchPanel.java`:

```java
package com.e2eechat.desktop;

import com.e2eechat.desktop.ui.Theme;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Searching one conversation, with results you can act on.
 *
 * <p>This was a prompt dialog followed by a second dialog holding the matches as text. You could
 * read them and go nowhere, which is the definition of a dead end.
 */
public class SearchPanel extends JPanel {

    private static final int MAX_HITS = 50;
    private static final int SNIPPET_CHARS = 70;

    private final ChatClient client;
    private final String peerId;
    private final Consumer<ChatMessage> onSelect;
    private final JPanel results = new JPanel();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM");

    public SearchPanel(ChatClient client, String peerId, Consumer<ChatMessage> onSelect) {
        this.client = client;
        this.peerId = peerId;
        this.onSelect = onSelect;

        setOpaque(false);
        setLayout(new BorderLayout());

        final JTextField query = new JTextField();
        query.setFont(Theme.font(Font.PLAIN, 14f));
        query.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        query.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                search(query.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                search(query.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                search(query.getText());
            }
        });

        results.setOpaque(false);
        results.setLayout(new BoxLayout(results, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(results);
        scroll.setBorder(null);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        top.add(query, BorderLayout.CENTER);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    private void search(String text) {
        results.removeAll();
        if (text == null || text.trim().isEmpty()) {
            revalidate();
            repaint();
            return;
        }

        List<ChatMessage> hits = client.getMessageRepository()
                .searchMessages(client.getClientId(), text.trim(), MAX_HITS);
        // The repository searches everything this user can see; this panel is about one peer.
        // Same filter the old dialog applied.
        hits.removeIf(m -> !m.getSender().equals(peerId) && !m.getReceiver().equals(peerId));

        if (hits.isEmpty()) {
            JLabel empty = new JLabel("No messages found");
            empty.setFont(Theme.font(Font.PLAIN, 13f));
            empty.setForeground(Theme.textSecondary());
            empty.setBorder(BorderFactory.createEmptyBorder(16, 12, 0, 12));
            results.add(empty);
        } else {
            for (ChatMessage hit : hits) {
                results.add(resultRow(hit));
            }
        }
        revalidate();
        repaint();
    }

    private JPanel resultRow(final ChatMessage hit) {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel who = new JLabel(client.displayNameFor(hit.getSender())
                + "  ·  " + dateFormat.format(new Date(hit.getTimestamp())));
        who.setFont(Theme.font(Font.PLAIN, 11f));
        who.setForeground(Theme.textSecondary());

        String content = hit.getContent() == null ? "" : hit.getContent();
        JLabel snippet = new JLabel(content.length() > SNIPPET_CHARS
                ? content.substring(0, SNIPPET_CHARS) + "…" : content);
        snippet.setFont(Theme.font(Font.PLAIN, 13f));
        snippet.setForeground(Theme.textPrimary());

        row.add(who, BorderLayout.NORTH);
        row.add(snippet, BorderLayout.CENTER);
        row.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                onSelect.accept(hit);
            }
        });
        return row;
    }
}
```

In `ChatWindow.java`, replace `showChatSearch()`:

```java
    private void showChatSearch() {
        String peerId = client.getReceiverId();
        if (peerId == null) {
            return;
        }
        openPanel(SidePanel.Side.RIGHT, "Search",
            panel -> new SearchPanel(client, peerId, hit -> {
                if (transcript != null) {
                    transcript.scrollTo(hit.getMessageId());
                }
            }));
    }
```

Delete the three `JOptionPane` calls the old method used.

- [ ] **Step 6: Verify**

Run: `./gradlew :chat-desktop:check`
Expected: BUILD SUCCESSFUL, and `grep -c JOptionPane chat-desktop/src/main/java/com/e2eechat/desktop/ChatWindow.java` returns 0.

- [ ] **Step 7: Commit**

```bash
git add chat-desktop/src/main/java/com/e2eechat/desktop/SearchPanel.java chat-desktop/src/main/java/com/e2eechat/desktop/ui/TranscriptPanel.java chat-desktop/src/main/java/com/e2eechat/desktop/ChatWindow.java chat-desktop/src/test/java/com/e2eechat/desktop/TranscriptScrollToTest.java
git commit -m "Make a search result something you can act on"
```

---

## Task 10: Inline the new-chat validation

**Files:**
- Modify: `chat-desktop/src/main/java/com/e2eechat/desktop/ConversationListPanel.java:274-297`
- Test: `chat-desktop/src/test/java/com/e2eechat/desktop/NewChatValidationTest.java`

**Interfaces:**
- Consumes: `PeerId.parse(String)`, `ChatClient.getClientId()`.
- Produces: `ConversationListPanel.validateNewChatId(String entered, String ownId)` — `static String`, returning the canonical peer id, or `null` when it is not usable; and `ConversationListPanel.newChatError(String entered, String ownId)` — `static String`, the message to show beneath the field, or `null` when the input is fine.

- [ ] **Step 1: Write the failing test**

Create `chat-desktop/src/test/java/com/e2eechat/desktop/NewChatValidationTest.java`:

```java
package com.e2eechat.desktop;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Validating a pasted peer id.
 *
 * <p>Pulled out as plain functions so the rules can be tested without a window. The rules
 * themselves are unchanged; what changes is that the answer appears under the field rather than in
 * a second window you must dismiss before you can correct what you typed.
 */
public class NewChatValidationTest {

    private static final String OWN = "0000000000000000000000000000beef";
    private static final String PEER = "4f3a91c28b7e05d6a1b2c3d4e5f60718";

    @Test
    public void aWellFormedIdIsAccepted() {
        assertEquals(PEER, ConversationListPanel.validateNewChatId(PEER, OWN));
        assertNull(ConversationListPanel.newChatError(PEER, OWN));
    }

    /** Accept whatever form was pasted; routing compares the canonical id byte for byte. */
    @Test
    public void groupedAndUpperCaseFormsAreAccepted() {
        assertEquals(PEER,
                ConversationListPanel.validateNewChatId("4F3A91C2 8B7E05D6 A1B2C3D4 E5F60718", OWN));
    }

    @Test
    public void somethingThatIsNotAnIdIsRejectedWithAReason() {
        assertNull(ConversationListPanel.validateNewChatId("hello", OWN));

        String error = ConversationListPanel.newChatError("hello", OWN);
        assertNotNull(error);
        assertEquals("That is not a peer id. An id is 32 hex characters.", error);
    }

    @Test
    public void yourOwnIdIsRejectedWithADifferentReason() {
        assertNull(ConversationListPanel.validateNewChatId(OWN, OWN));
        assertEquals("That is your own id.", ConversationListPanel.newChatError(OWN, OWN));
    }

    @Test
    public void anEmptyFieldIsNotAnError() {
        assertNull(ConversationListPanel.newChatError("", OWN));
        assertNull(ConversationListPanel.newChatError("   ", OWN));
        assertNull(ConversationListPanel.newChatError(null, OWN));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :chat-desktop:test --tests '*NewChatValidationTest*'`
Expected: FAIL — compile error, `cannot find symbol: method validateNewChatId(String,String)`.

- [ ] **Step 3: Extract the rules**

In `ConversationListPanel.java`:

```java
    /** The canonical id for what was typed, or null when it cannot be used. */
    static String validateNewChatId(String entered, String ownId) {
        if (entered == null || entered.trim().isEmpty()) {
            return null;
        }
        String peerId = PeerId.parse(entered);
        if (peerId == null || peerId.equals(ownId)) {
            return null;
        }
        return peerId;
    }

    /** What to show beneath the field, or null while there is nothing to say. */
    static String newChatError(String entered, String ownId) {
        if (entered == null || entered.trim().isEmpty()) {
            return null;
        }
        String peerId = PeerId.parse(entered);
        if (peerId == null) {
            return "That is not a peer id. An id is 32 hex characters.";
        }
        if (peerId.equals(ownId)) {
            return "That is your own id.";
        }
        return null;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :chat-desktop:test --tests '*NewChatValidationTest*'`
Expected: PASS, 5 tests.

- [ ] **Step 5: Replace the dialog with an inline field**

Replace `promptNewChat()`:

```java
    /**
     * Reveals the new-chat field in the sidebar header.
     *
     * <p>This was a prompt dialog whose rejection opened a second dialog, so correcting a mistyped
     * id meant dismissing a window before you could reach the box you had typed it in. The error
     * now appears under the field, and what you typed is still there to fix.
     */
    private void promptNewChat() {
        newChatField.setText("");
        newChatError.setText(" ");
        newChatField.setVisible(true);
        newChatError.setVisible(true);
        revalidate();
        newChatField.requestFocusInWindow();
    }

    /** Builds the field once, in the sidebar header, hidden until it is wanted. */
    private void installNewChatField() {
        newChatField.setFont(Theme.font(Font.PLAIN, 14f));
        newChatField.setVisible(false);
        newChatError.setFont(Theme.font(Font.PLAIN, 11f));
        newChatError.setForeground(Theme.danger());
        newChatError.setVisible(false);

        newChatField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                showError();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                showError();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                showError();
            }

            private void showError() {
                String message = newChatError(newChatField.getText(), client.getClientId());
                newChatError.setText(message == null ? " " : message);
            }
        });

        newChatField.addActionListener(e -> {
            String peerId = validateNewChatId(newChatField.getText(), client.getClientId());
            if (peerId == null) {
                return;
            }
            hideNewChatField();
            openConversation(peerId);
        });

        newChatField.getInputMap(WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-new-chat");
        newChatField.getActionMap().put("cancel-new-chat", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                hideNewChatField();
            }
        });
    }

    private void hideNewChatField() {
        newChatField.setVisible(false);
        newChatError.setVisible(false);
        newChatField.setText("");
        revalidate();
        repaint();
    }
```

Declare the two components beside the panel's other fields, add them to the header container
beneath the existing title row, and call `installNewChatField()` from the constructor:

```java
    private final JTextField newChatField = new JTextField();
    private final JLabel newChatError = new JLabel(" ");
```

The error label is initialised to a single space rather than an empty string so the row keeps its
height and the field below it does not jump as messages appear and clear.

- [ ] **Step 6: Verify no replaceable dialogs remain**

Run: `./gradlew :chat-desktop:check`
Expected: BUILD SUCCESSFUL.

Run: `grep -rn "JOptionPane.show" chat-desktop/src/main/java/`
Expected: five results, all in `Main.java`, all preceding exit.

- [ ] **Step 7: Commit**

```bash
git add chat-desktop/src/main/java/com/e2eechat/desktop/ConversationListPanel.java chat-desktop/src/test/java/com/e2eechat/desktop/NewChatValidationTest.java
git commit -m "Put the peer id error under the box you typed it in"
```

---

## Task 11: Update the documentation and run the whole suite

**Files:**
- Modify: `docs/qa_script.md`
- Modify: `README.md`

- [ ] **Step 1: Update the QA script**

In `docs/qa_script.md`, case 4 references the safety number via "the shield button in the chat header, or the chat menu". Update it to say the shield opens **Chat info**, and that both sides must show identical safety numbers there. Add a step to case 4: mark the peer verified on one side and confirm the shield appears in the sidebar row.

Add a case 11, **Panels and theme**: open Settings from the sidebar menu and Chat info from the header; confirm only one is open at a time, that Escape and a click on the dimmed area both close them; toggle Night mode and confirm that a popup menu, a tooltip and a scrollbar all follow — those were the components that stayed light before.

- [ ] **Step 2: Update the README**

The README's step 4 says the peer id is under **Menu → Settings → Copy my id**, which is still true. Add that a conversation's safety number is under the shield in the chat header.

- [ ] **Step 3: Run everything**

Run: `./gradlew :core-shared:check :chat-server:check :chat-desktop:check :chat-desktop:integTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add docs/qa_script.md README.md
git commit -m "Describe the panels the QA script now has to check"
```

---

## Notes for the implementer

**The mobile module does not build.** `./gradlew build` currently fails on two Android lint errors — `android:tint` should be `app:tint` in `chat-mobile/src/main/res/layout/fragment_chat.xml:67` and `item_message_out.xml:59`. They are in unrelated in-progress work and are **not yours to fix**. Verify with `:chat-desktop:check` and the other module-scoped commands in this plan, not with a bare `./gradlew build`.

**Do not commit the uncommitted mobile changes.** The working tree has 19 modified and untracked paths under `chat-mobile/`. Every `git add` in this plan names explicit files for that reason. Never use `git add -A` or `git add .`.

**Task 2 is the risky one.** It is the only task that removes working mechanism. It is deliberately placed while Settings is still the only caller, so if the extraction breaks something it breaks in isolation with nothing built on top of it. If it goes wrong, revert that commit alone and the rest of the plan is unaffected.

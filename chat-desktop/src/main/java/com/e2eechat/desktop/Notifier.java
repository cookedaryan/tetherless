package com.e2eechat.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.AWTException;
import java.awt.Frame;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tells the user about a message they are not looking at.
 *
 * <h2>Why this is not simply "show the message"</h2>
 * A notification leaves the application. The operating system renders it on the lock screen, keeps
 * a copy in a notification centre, and on Windows may forward it to a phone. Anything put in one
 * has left the end-to-end guarantee behind, so the message text is only included when the user has
 * asked for it; by default a notification says who wrote, and nothing about what.
 *
 * <p>Everything here degrades to doing nothing. {@link SystemTray} is absent on some desktops and
 * unavailable under some session types, and a chat client that refuses to start because it could
 * not create a tray icon would be a poor trade.
 */
public final class Notifier {

    private static final Logger LOG = LoggerFactory.getLogger(Notifier.class);

    private final JFrame window;
    private TrayIcon trayIcon;
    private int unread;

    public Notifier(JFrame window) {
        this.window = window;
        install();
    }

    private void install() {
        if (!SystemTray.isSupported()) {
            LOG.info("No system tray on this desktop; notifications will be badge-only");
            return;
        }
        Image icon = loadIcon();
        if (icon == null) {
            return;
        }
        try {
            trayIcon = new TrayIcon(icon, "Tetherless");
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> SwingUtilities.invokeLater(this::focusWindow));
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException | RuntimeException e) {
            LOG.warn("Could not install the tray icon: {}", e.toString());
            trayIcon = null;
        }
    }

    private Image loadIcon() {
        try (InputStream in = Notifier.class.getResourceAsStream("/tetherless-256.png")) {
            if (in == null) {
                LOG.warn("Tray icon resource is missing");
                return null;
            }
            BufferedImage image = ImageIO.read(in);
            return image;
        } catch (IOException e) {
            LOG.warn("Could not read the tray icon: {}", e.toString());
            return null;
        }
    }

    /**
     * Announces a message from {@code sender}.
     *
     * <p>Silent when the window is focused: the user is already looking at the application, and
     * the transcript has just drawn the message they would be told about.
     */
    public void messageArrived(String sender, String text) {
        if (window.isActive()) {
            return;
        }
        unread++;
        updateBadge();

        if (!DesktopConfig.notificationsPreference() || trayIcon == null) {
            return;
        }
        String body = DesktopConfig.notificationPreviewPreference() && text != null
                ? text
                : "New message";
        trayIcon.displayMessage(sender, body, TrayIcon.MessageType.NONE);
    }

    /** Called when the user is looking at the conversation again. */
    public void clear() {
        if (unread == 0) {
            return;
        }
        unread = 0;
        updateBadge();
    }

    /**
     * Carries the unread count on the tray icon's tooltip.
     *
     * <p>Not a taskbar badge: {@code java.awt.Taskbar} arrived in Java 9 and this build targets 8
     * throughout, so that AWT can stay usable from the Android module.
     */
    private void updateBadge() {
        if (trayIcon == null) {
            return;
        }
        trayIcon.setToolTip(unread == 0
                ? "Tetherless"
                : "Tetherless - " + unread + (unread == 1 ? " unread" : " unread messages"));
    }

    private void focusWindow() {
        window.setExtendedState(window.getExtendedState() & ~Frame.ICONIFIED);
        window.setVisible(true);
        window.toFront();
        window.requestFocus();
    }

    /** Takes the tray icon back down. Without this the icon outlives the process on Windows. */
    public void dispose() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
    }
}

package com.e2eechat.desktop.ui;

import java.awt.Graphics2D;

/**
 * The surface the transcript sits on.
 *
 * <p>This used to paint a Telegram-style diagonal gradient overlaid with a repeating doodle tile.
 * The design it now follows puts the conversation on a plain surface and lets the bubbles carry
 * all of the colour, so the gradient and the pattern are gone rather than turned down: a texture
 * behind translucent bubbles is exactly the thing that makes light-mode text hard to read.
 *
 * <p>Kept as a single call rather than a {@code fillRect} at each of its three call sites, so
 * "what the chat surface looks like" stays one decision in one place.
 */
public final class ChatWallpaper {

    private ChatWallpaper() {
    }

    public static void paint(Graphics2D g, int w, int h) {
        g.setColor(Theme.chatBg());
        g.fillRect(0, 0, w, h);
    }
}

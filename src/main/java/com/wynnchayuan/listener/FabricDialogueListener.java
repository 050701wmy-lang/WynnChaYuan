package com.wynnchayuan.listener;

import com.wynnchayuan.CollectorConfig;
import com.wynnchayuan.WynnChaYuan;
import com.wynnchayuan.render.DialogueRewriter;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Stable fallback for dialogue replacement.
 *
 * <p>Wynntils 4.x snapshots have changed their dialogue model several times.  The server
 * message itself is a Minecraft/Fabric contract, so replace the text there after Wynntils
 * has had a chance to inspect the original message.  This keeps NPC dialogue working when
 * {@code NpcDialogueEvent} is renamed, delayed, or not emitted by a particular 4.x build.
 */
public final class FabricDialogueListener {
    private static final String DIALOGUE_FONT = "hud/dialogue/";

    private FabricDialogueListener() {}

    public static void register() {
        ClientReceiveMessageEvents.MODIFY_GAME.register(FabricDialogueListener::rewrite);
    }

    private static Component rewrite(Component message, boolean overlay) {
        if (!overlay || message == null || !isDialogue(message)
                || WynnChaYuan.translations() == null) {
            return message;
        }
        CollectorConfig.DialogueMode replace = CollectorConfig.DialogueMode.REPLACE;
        if (WynnChaYuan.config().dialogueMode() != replace
                && WynnChaYuan.config().choiceMode() != replace) {
            return message;
        }
        try {
            Component translated = DialogueRewriter.rewrite(
                    message, WynnChaYuan.translations());
            if (translated != null) {
                WynnChaYuan.store().noteEvent("dialogue.fabricReplaced");
                return translated;
            }
            WynnChaYuan.store().noteEvent("dialogue.fabricSeen");
        } catch (Throwable t) {
            WynnChaYuan.store().noteEvent("dialogue.fabricError");
        }
        return message;
    }

    private static boolean isDialogue(Component message) {
        final boolean[] found = {false};
        message.visit((style, text) -> {
            if (style.getFont() instanceof FontDescription.Resource(Identifier id)
                    && id.getPath().startsWith(DIALOGUE_FONT)) {
                found[0] = true;
                return Optional.of(Boolean.TRUE);
            }
            return Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return found[0];
    }
}

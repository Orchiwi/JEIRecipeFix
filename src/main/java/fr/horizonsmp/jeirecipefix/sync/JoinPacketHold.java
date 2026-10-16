package fr.horizonsmp.jeirecipefix.sync;

import org.bukkit.entity.Player;

/**
 * Holds back the vanilla recipe-update packet a player is sent on joining, so that the recipe
 * payload can reach the client before it.
 *
 * <p>JEI starts the moment that packet is handled, and only uses the server's recipes if Fabric's
 * recipe sync arrived first; otherwise it starts on the client's own recipes and warns in chat that
 * the server provides none. A Fabric server sends the sync right before that packet. A Paper server
 * sends the packet before any plugin hears of the player, so without holding it the payload is
 * always second.
 */
public interface JoinPacketHold {

    /** Whether this player's join packet is being held right now. */
    boolean isHolding(Player player);

    /**
     * Lets the held packet go, after anything already sent to the player, or stops a packet that
     * has not been sent yet from being held. Does nothing once it has been released.
     */
    void release(Player player);

    /** Holds nothing: the plugin's behaviour before this existed. */
    JoinPacketHold NONE = new JoinPacketHold() {
        @Override
        public boolean isHolding(Player player) {
            return false;
        }

        @Override
        public void release(Player player) {
        }
    };
}

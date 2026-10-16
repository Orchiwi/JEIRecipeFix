package fr.horizonsmp.jeirecipefix.nms;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JoinPacketHoldHandlerTest {

    /** Stands in for the recipe-update packet. */
    private record Recipes(String label) {
    }

    /** Stands in for the packet that takes a connection back to configuration. */
    private record Reconfigure() {
    }

    private final AtomicInteger timeouts = new AtomicInteger();

    private JoinPacketHoldHandler handler() {
        return new JoinPacketHoldHandler(Recipes.class, Reconfigure.class, 10_000, timeouts::incrementAndGet);
    }

    private static List<Object> sent(EmbeddedChannel channel) {
        List<Object> out = new ArrayList<>();
        for (Object msg; (msg = channel.readOutbound()) != null; ) {
            out.add(msg);
        }
        return out;
    }

    @Test
    void holdsTheFirstRecipePacketAndLetsEverythingElsePass() {
        JoinPacketHoldHandler hold = handler();
        EmbeddedChannel channel = new EmbeddedChannel(hold);

        channel.writeAndFlush("login");
        channel.writeAndFlush(new Recipes("join"));
        channel.writeAndFlush("recipe book");

        assertEquals(List.of("login", "recipe book"), sent(channel));
        assertTrue(hold.isHolding());
    }

    @Test
    void releaseSendsTheHeldPacketAfterWhatWasWrittenBeforeIt() {
        JoinPacketHoldHandler hold = handler();
        EmbeddedChannel channel = new EmbeddedChannel(hold);

        channel.writeAndFlush(new Recipes("join"));
        channel.writeAndFlush("payload");
        hold.release();
        channel.runPendingTasks();

        assertEquals(List.of("payload", new Recipes("join")), sent(channel));
        assertFalse(hold.isHolding());

        // Only the first one is ever held.
        channel.writeAndFlush(new Recipes("reload"));
        assertEquals(List.of(new Recipes("reload")), sent(channel));
    }

    @Test
    void aSecondRecipePacketDoesTheJobAndTheHeldOneIsDropped() {
        JoinPacketHoldHandler hold = handler();
        EmbeddedChannel channel = new EmbeddedChannel(hold);

        channel.writeAndFlush(new Recipes("join"));
        channel.writeAndFlush("payload");
        channel.writeAndFlush(new Recipes("trigger"));
        hold.release();
        channel.runPendingTasks();

        assertEquals(List.of("payload", new Recipes("trigger")), sent(channel),
                "two recipe packets after one sync would make JEI drop the synced recipes");
    }

    @Test
    void releasedBeforeThePacketCameMeansItIsNeverHeld() {
        JoinPacketHoldHandler hold = handler();
        EmbeddedChannel channel = new EmbeddedChannel(hold);

        hold.release();
        channel.runPendingTasks();
        channel.writeAndFlush(new Recipes("join"));

        assertEquals(List.of(new Recipes("join")), sent(channel));
        assertFalse(hold.isHolding());
    }

    @Test
    void givesUpAfterItsTimeLimit() {
        JoinPacketHoldHandler hold = handler();
        EmbeddedChannel channel = new EmbeddedChannel(hold);

        channel.writeAndFlush(new Recipes("join"));
        channel.advanceTimeBy(9, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        assertEquals(List.of(), sent(channel));

        channel.advanceTimeBy(2, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        assertEquals(List.of(new Recipes("join")), sent(channel));
        assertEquals(1, timeouts.get());

        // A release that comes after the time limit has nothing left to send.
        hold.release();
        channel.runPendingTasks();
        assertEquals(List.of(), sent(channel));
    }

    @Test
    void dropsTheHeldPacketWhenTheConnectionLeavesPlay() {
        JoinPacketHoldHandler hold = handler();
        EmbeddedChannel channel = new EmbeddedChannel(hold);

        channel.writeAndFlush(new Recipes("join"));
        channel.writeAndFlush(new Reconfigure());
        hold.release();
        channel.runPendingTasks();

        assertEquals(List.of(new Reconfigure()), sent(channel));
    }

    @Test
    void sendsTheHeldPacketOnWhenTakenOutOfThePipeline() {
        JoinPacketHoldHandler hold = handler();
        EmbeddedChannel channel = new EmbeddedChannel(hold);

        channel.writeAndFlush(new Recipes("join"));
        channel.pipeline().remove(hold);

        assertEquals(List.of(new Recipes("join")), sent(channel));
    }

    @Test
    void forgetsTheHeldPacketWhenTheConnectionCloses() {
        JoinPacketHoldHandler hold = handler();
        EmbeddedChannel channel = new EmbeddedChannel(hold);

        channel.writeAndFlush(new Recipes("join"));
        channel.close();
        channel.advanceTimeBy(20, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();

        assertFalse(hold.isHolding());
        assertEquals(0, timeouts.get());
    }
}

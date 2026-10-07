package ipl.sable.diagnostics;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import java.util.UUID;

/** Operator-only finite arming; no JVM flag and no arbitrary remote operation. */
public final class IplIgnitionTraceControl {
    private static final String RPC = "ipl.sable.diagnostics.client.IplIgnitionTraceRemoteCallables.";
    private static boolean initialized;
    public static void init() {
        if (initialized) return;
        initialized = true;
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, event -> event.getDispatcher().register(
            Commands.literal("ipl_ignite_trace").requires(s -> s.hasPermission(2))
                .then(Commands.literal("status").executes(c -> {
                    var w = IplIgnitionTrace.window(IplIgnitionTrace.Side.SERVER);
                    c.getSource().sendSuccess(() -> Component.literal(w == null ? "Ignition trace off" : w.status()), false);
                    return 1;
                }))
                .then(Commands.literal("stop").executes(c -> {
                    var w = IplIgnitionTrace.window(IplIgnitionTrace.Side.SERVER);
                    if (w != null) {
                        var target = c.getSource().getServer().getPlayerList().getPlayer(w.player);
                        if (target != null) McRemoteProcedureCall.tellClientToInvoke(target, RPC + "stop", w.token);
                    }
                    IplIgnitionTrace.clear(IplIgnitionTrace.Side.SERVER);
                    c.getSource().sendSuccess(() -> Component.literal("Ignition trace stopped"), false);
                    return 1;
                }))
                .then(Commands.literal("start").then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("target", BlockPosArgument.blockPos())
                    .then(Commands.argument("radius", IntegerArgumentType.integer(0, IgnitionTraceWindow.MAX_RADIUS))
                    .then(Commands.argument("attempts", IntegerArgumentType.integer(1, IgnitionTraceWindow.MAX_ATTEMPTS))
                    .then(Commands.argument("seconds", IntegerArgumentType.integer(1, IgnitionTraceWindow.MAX_SECONDS))
                    .executes(c -> {
                        ServerPlayer target = EntityArgument.getPlayer(c, "player");
                        var pos = BlockPosArgument.getBlockPos(c, "target");
                        int radius = IntegerArgumentType.getInteger(c, "radius"),
                            attempts = IntegerArgumentType.getInteger(c, "attempts"),
                            seconds = IntegerArgumentType.getInteger(c, "seconds");
                        UUID token = UUID.randomUUID();
                        String dimension = target.level().dimension().location().toString();
                        // Stop any previous client window before replacing the server scope.
                        var previous = IplIgnitionTrace.window(IplIgnitionTrace.Side.SERVER);
                        if (previous != null) {
                            var oldPlayer = c.getSource().getServer().getPlayerList().getPlayer(previous.player);
                            if (oldPlayer != null) McRemoteProcedureCall.tellClientToInvoke(oldPlayer, RPC + "stop", previous.token);
                        }
                        IplIgnitionTrace.arm(IplIgnitionTrace.Side.SERVER, new IgnitionTraceWindow(token,
                            target.getUUID(), dimension, pos.getX(), pos.getY(), pos.getZ(), radius, attempts, seconds, System::nanoTime));
                        McRemoteProcedureCall.tellClientToInvoke(target, RPC + "arm", token, target.getUUID(), dimension,
                            pos.getX(), pos.getY(), pos.getZ(), radius, attempts, seconds);
                        c.getSource().sendSuccess(() -> Component.literal("Ignition trace armed on server; client arming sent. token="
                            + token + " attempts=" + attempts + " seconds=" + seconds), false);
                        return 1;
                    })))))))));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, e -> IplIgnitionTrace.clear(IplIgnitionTrace.Side.SERVER));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
            var w = IplIgnitionTrace.window(IplIgnitionTrace.Side.SERVER);
            if (w != null && w.player.equals(e.getEntity().getUUID())) IplIgnitionTrace.clear(IplIgnitionTrace.Side.SERVER);
        });
    }
}

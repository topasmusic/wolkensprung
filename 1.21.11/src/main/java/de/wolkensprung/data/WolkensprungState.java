package de.wolkensprung.data;

import de.wolkensprung.quest.WolkensprungQuest;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

public final class WolkensprungState extends PersistentState {
    private static final String ID = "wolkensprung_state";
    public static final PersistentStateType<WolkensprungState> TYPE =
            new PersistentStateType<>(ID, WolkensprungState::new, NbtCompound.CODEC.xmap(WolkensprungState::fromNbt, WolkensprungState::toNbt), DataFixTypes.LEVEL);

    private NbtCompound runtimeData = new NbtCompound();

    private WolkensprungState() {}

    private static WolkensprungState fromNbt(NbtCompound nbt) {
        WolkensprungState state = new WolkensprungState();
        if (nbt != null) {
            state.runtimeData = nbt.getCompoundOrEmpty("runtime");
        }
        return state;
    }

    private static NbtCompound toNbt(WolkensprungState state) {
        NbtCompound root = new NbtCompound();
        root.put("runtime", WolkensprungQuest.writeToNbt());
        return root;
    }

    public static WolkensprungState get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public void updateFromRuntime() {
        markDirty();
    }

    public void applyToRuntime() {
        WolkensprungQuest.readFromNbt(runtimeData);
    }
}
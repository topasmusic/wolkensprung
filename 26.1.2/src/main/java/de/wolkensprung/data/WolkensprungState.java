package de.wolkensprung.data;

import de.wolkensprung.quest.WolkensprungQuest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class WolkensprungState extends SavedData {
    private static final Identifier ID = Identifier.fromNamespaceAndPath("wolkensprung", "state");
    public static final SavedDataType<WolkensprungState> TYPE =
            new SavedDataType<>(ID, WolkensprungState::new, CompoundTag.CODEC.xmap(WolkensprungState::fromNbt, WolkensprungState::toNbt), DataFixTypes.LEVEL);

    private CompoundTag runtimeData = new CompoundTag();

    private WolkensprungState() {}

    private static WolkensprungState fromNbt(CompoundTag nbt) {
        WolkensprungState state = new WolkensprungState();
        if (nbt != null) {
            state.runtimeData = nbt.getCompoundOrEmpty("runtime");
        }
        return state;
    }

    private static CompoundTag toNbt(WolkensprungState state) {
        CompoundTag root = new CompoundTag();
        root.put("runtime", WolkensprungQuest.writeToNbt());
        return root;
    }

    public static WolkensprungState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public void updateFromRuntime() {
        setDirty();
    }

    public void applyToRuntime() {
        WolkensprungQuest.readFromNbt(runtimeData);
    }
}

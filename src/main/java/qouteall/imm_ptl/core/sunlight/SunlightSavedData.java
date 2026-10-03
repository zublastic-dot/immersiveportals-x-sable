package qouteall.imm_ptl.core.sunlight;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/** One profile per save, rather than a machine-global or individual player's setting. */
public final class SunlightSavedData extends SavedData {
    private SunlightProfile profile = SunlightProfile.disabled();
    private long revision = 1;
    public SunlightProfile profile() { return profile; }
    public long revision() { return revision; }
    public static SunlightSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
            new Factory<>(SunlightSavedData::new, SunlightSavedData::load, null), "imm_ptl_sunlight");
    }
    public boolean update(SunlightProfile value) {
        if (value.equals(profile)) return false;
        profile = value;
        revision = revision == Long.MAX_VALUE ? 1 : revision + 1;
        setDirty();
        return true;
    }
    static SunlightSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SunlightSavedData result = new SunlightSavedData();
        try {
            if (tag.getInt("schema") != SunlightProfile.SCHEMA || !tag.contains("enabled", Tag.TAG_BYTE)
                || !tag.contains("rotation", Tag.TAG_DOUBLE) || !tag.contains("clock", Tag.TAG_STRING))
                throw new IllegalArgumentException("Unsupported or incomplete sunlight profile");
            result.profile = new SunlightProfile(tag.getBoolean("enabled"), tag.getDouble("rotation"),
                SunlightProfile.Clock.valueOf(tag.getString("clock")));
            result.revision = Math.max(1, tag.getLong("revision"));
        } catch (IllegalArgumentException invalid) {
            LogUtils.getLogger().error("[IP sunlight] Saved profile is invalid; sunlight gameplay remains disabled", invalid);
        }
        return result;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("schema", SunlightProfile.SCHEMA);
        tag.putBoolean("enabled", profile.enabled());
        tag.putDouble("rotation", profile.pathRotationDegrees());
        tag.putString("clock", profile.clock().name());
        tag.putLong("revision", revision);
        return tag;
    }
}

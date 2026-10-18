package com.nstut.simplymirrors.block;
import java.util.function.Supplier;
import net.minecraft.world.level.block.entity.BlockEntityType;
public final class MirrorContent {
    public static Supplier<BlockEntityType<MirrorBlockEntity>> blockEntityType;
    private MirrorContent() { }
}

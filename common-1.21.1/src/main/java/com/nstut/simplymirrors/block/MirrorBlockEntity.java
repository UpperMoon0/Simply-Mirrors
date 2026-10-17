package com.nstut.simplymirrors.block;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
/** An indexed surface marker. Contains no media, player state or per-tick work. */
public final class MirrorBlockEntity extends BlockEntity { public MirrorBlockEntity(BlockPos pos, BlockState state) { super(MirrorContent.blockEntityType.get(), pos, state); } }

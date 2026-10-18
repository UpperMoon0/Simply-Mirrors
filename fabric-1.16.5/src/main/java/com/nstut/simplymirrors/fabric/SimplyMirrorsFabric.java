package com.nstut.simplymirrors.fabric;
import com.nstut.simplymirrors.SimplyMirrors;
import com.nstut.simplymirrors.block.*;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.fabricmc.fabric.api.client.itemgroup.FabricItemGroupBuilder;

public final class SimplyMirrorsFabric implements ModInitializer {
    public static final Block MIRROR = new MirrorBlock(BlockBehaviour.Properties.of(net.minecraft.world.level.material.Material.METAL).strength(0.8F).sound(SoundType.METAL));
    public static final CreativeModeTab TAB = FabricItemGroupBuilder.create(id("main")).icon(() -> new ItemStack(SimplyMirrorsFabric.MIRROR_ITEM)).build();
    public static final Item MIRROR_ITEM = new BlockItem(MIRROR, new Item.Properties().tab(TAB));
    @Override public void onInitialize() {
        Registry.register(Registry.BLOCK, id("mirror"), MIRROR);
        Registry.register(Registry.ITEM, id("mirror"), MIRROR_ITEM);
        BlockEntityType<MirrorBlockEntity> type = Registry.register(Registry.BLOCK_ENTITY_TYPE, id("mirror"), BlockEntityType.Builder.of(MirrorBlockEntity::new, MIRROR).build(null));
        MirrorContent.blockEntityType = () -> type;
        
        SimplyMirrors.initialize();
    }
    private static ResourceLocation id(String path) { return new ResourceLocation(SimplyMirrors.MOD_ID, path); }
}

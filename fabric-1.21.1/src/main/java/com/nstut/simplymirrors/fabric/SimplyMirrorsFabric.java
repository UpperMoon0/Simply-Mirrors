package com.nstut.simplymirrors.fabric;
import com.nstut.simplymirrors.SimplyMirrors;
import com.nstut.simplymirrors.block.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.network.chat.Component;

public final class SimplyMirrorsFabric implements ModInitializer {
    public static final Block MIRROR = new MirrorBlock(BlockBehaviour.Properties.of().strength(0.8F).sound(SoundType.WOOD).noOcclusion());
    
    public static final Item MIRROR_ITEM = new BlockItem(MIRROR, new Item.Properties());
    @Override public void onInitialize() {
        Registry.register(BuiltInRegistries.BLOCK, id("mirror"), MIRROR);
        Registry.register(BuiltInRegistries.ITEM, id("mirror"), MIRROR_ITEM);
        BlockEntityType<MirrorBlockEntity> type = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, id("mirror"), FabricBlockEntityTypeBuilder.create(MirrorBlockEntity::new, MIRROR).build());
        MirrorContent.blockEntityType = () -> type;
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, id("main"), net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup.builder()
            .title(Component.translatable("itemGroup.simply_mirrors")).icon(() -> new ItemStack(MIRROR_ITEM))
            .displayItems((parameters, output) -> output.accept(MIRROR_ITEM)).build());
        SimplyMirrors.initialize();
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(SimplyMirrors.MOD_ID, path); }
}

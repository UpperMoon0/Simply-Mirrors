package com.nstut.simplymirrors.neoforge;
import com.nstut.simplymirrors.SimplyMirrors;
import com.nstut.simplymirrors.block.*;
import net.neoforged.fml.common.Mod;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;

@Mod(SimplyMirrors.MOD_ID)
public final class SimplyMirrorsNeoforge {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, SimplyMirrors.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, SimplyMirrors.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SimplyMirrors.MOD_ID);
    public static final DeferredHolder<Block, Block> MIRROR = BLOCKS.register("mirror", () -> new MirrorBlock(BlockBehaviour.Properties.of().strength(0.8F).sound(SoundType.WOOD).noOcclusion()));
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SimplyMirrors.MOD_ID);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
        .title(Component.translatable("itemGroup.simply_mirrors")).icon(() -> new ItemStack(SimplyMirrorsNeoforge.MIRROR_ITEM.get()))
        .displayItems((parameters, output) -> output.accept(SimplyMirrorsNeoforge.MIRROR_ITEM.get())).build());
    public static final DeferredHolder<Item, BlockItem> MIRROR_ITEM = ITEMS.register("mirror", () -> new BlockItem(MIRROR.get(), new Item.Properties()));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MirrorBlockEntity>> MIRROR_ENTITY = ENTITIES.register("mirror", () -> BlockEntityType.Builder.of(MirrorBlockEntity::new, MIRROR.get()).build(null));
    public SimplyMirrorsNeoforge(net.neoforged.bus.api.IEventBus bus) {
        MirrorContent.blockEntityType = () -> MIRROR_ENTITY.get();
        BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); TABS.register(bus);
        SimplyMirrors.initialize();
    }
}

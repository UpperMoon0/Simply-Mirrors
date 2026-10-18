package com.nstut.simplymirrors.forge;
import com.nstut.simplymirrors.SimplyMirrors;
import com.nstut.simplymirrors.block.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;

@Mod(SimplyMirrors.MOD_ID)
public final class SimplyMirrorsForge {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, SimplyMirrors.MOD_ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, SimplyMirrors.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, SimplyMirrors.MOD_ID);
    public static final RegistryObject<Block> MIRROR = BLOCKS.register("mirror", () -> new MirrorBlock(BlockBehaviour.Properties.of().strength(0.8F).sound(SoundType.METAL)));
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, SimplyMirrors.MOD_ID);
    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
        .title(Component.translatable("itemGroup.simply_mirrors")).icon(() -> new ItemStack(SimplyMirrorsForge.MIRROR_ITEM.get()))
        .displayItems((parameters, output) -> output.accept(SimplyMirrorsForge.MIRROR_ITEM.get())).build());
    public static final RegistryObject<BlockItem> MIRROR_ITEM = ITEMS.register("mirror", () -> new BlockItem(MIRROR.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<MirrorBlockEntity>> MIRROR_ENTITY = ENTITIES.register("mirror", () -> BlockEntityType.Builder.of(MirrorBlockEntity::new, MIRROR.get()).build(null));
    public SimplyMirrorsForge() { net.minecraftforge.eventbus.api.IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        MirrorContent.blockEntityType = () -> MIRROR_ENTITY.get();
        BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); TABS.register(bus);
        SimplyMirrors.initialize();
    }
}

package com.pg85.otg.fabric.mixins;

import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.pg85.otg.constants.Constants;
import com.pg85.otg.core.OTG;
import com.pg85.otg.fabric.biome.OTGBiomeProvider;
import com.pg85.otg.fabric.dimensions.OTGDimensionTypeHelper;
import com.pg85.otg.fabric.gen.OTGNoiseChunkGenerator;
import com.pg85.otg.interfaces.IWorldConfig;
import com.pg85.otg.util.logging.LogCategory;
import com.pg85.otg.util.logging.LogLevel;
import net.fabricmc.fabric.mixin.datagen.DynamicRegistryManagerAccessor;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.BuiltinRegistries;
import net.minecraft.data.worldgen.SurfaceRuleData;
import net.minecraft.data.worldgen.TerrainProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.dedicated.DedicatedServerProperties.WorldGenProperties;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.logging.Logger;

@Mixin(WorldGenSettings.class)
public abstract class WorldGenSettingsMixin {
    @Inject(method = "create", at = @At("HEAD"), cancellable = true)
    private static void injectWorldCreation(RegistryAccess ra, WorldGenProperties wgp, CallbackInfoReturnable<WorldGenSettings> cir)
    {
        String levelType = wgp.levelType().trim().toLowerCase();

        //for the otg level type
        if(levelType.equals(Constants.MOD_ID_SHORT)) {
            //does this work this early?
            OTG.getEngine().getLogger().log(LogLevel.INFO, LogCategory.MAIN, "Loading OTG world...");

            //parse the seed
            OptionalLong parsedInputtedSeed = WorldGenSettings.parseSeed(wgp.levelSeed());
            long seed = 0;
            if(parsedInputtedSeed.isEmpty()) {
                seed = (new Random()).nextLong(); //gen random seed if none given
            }
            else {
                seed = parsedInputtedSeed.getAsLong(); //otherwise use the inputted seed
            }

            Registry<DimensionType> dimensionTypeRegistry = ra.registryOrThrow(Registry.DIMENSION_TYPE_REGISTRY);
            Registry<Biome> biomeRegistry = ra.registryOrThrow(Registry.BIOME_REGISTRY);
            Registry<StructureSet> structureSetRegistry = ra.registryOrThrow(Registry.STRUCTURE_SET_REGISTRY);
            Registry<LevelStem> levelStemRegistry = DimensionType.defaultDimensions(ra, seed);

            NoiseSettings noiseSettings = getNoiseSettings(OTG.getEngine().getPresetLoader().getPresetByShortNameOrFolderName("Default").getWorldConfig());
            cir.setReturnValue(OTGDimensionTypeHelper.createOTGSettings(ra, seed, true, false, "Default"));

        }
    }

    // Taken from vanilla NoiseRouterData::overworldWithoutCaves
    private static NoiseRouterWithOnlyNoises getNoiseRouter(NoiseSettings noiseSettings) {
        DensityFunction shiftX = getFunction("shift_x");
        DensityFunction shiftZ = getFunction("shift_z");
        return new NoiseRouterWithOnlyNoises(
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.shiftedNoise2d(shiftX, shiftZ, 0.25, BuiltinRegistries.NOISE.getHolderOrThrow(Noises.TEMPERATURE)),
                DensityFunctions.shiftedNoise2d(shiftX, shiftZ, 0.25, BuiltinRegistries.NOISE.getHolderOrThrow(Noises.VEGETATION)),
                getFunction("overworld/continents"),
                getFunction("overworld/erosion"),
                getFunction("overworld/depth"),
                getFunction("overworld/ridges"),
                DensityFunctions.mul(DensityFunctions.constant(4.0D), DensityFunctions.mul(getFunction("overworld/depth"), DensityFunctions.cache2d(getFunction("overworld/factor"))).quarterNegative()),
                DensityFunctions.mul(DensityFunctions.interpolated(DensityFunctions.blendDensity(DensityFunctions.slide(noiseSettings, getFunction("overworld/sloped_cheese")))), DensityFunctions.constant(0.64D)).squeeze(),
                DensityFunctions.zero(),
                DensityFunctions.zero(),
                DensityFunctions.zero()
        );
    }

    private static DensityFunction getFunction(String string) {
        return BuiltinRegistries.DENSITY_FUNCTION.getHolderOrThrow(ResourceKey.create(Registry.DENSITY_FUNCTION_REGISTRY, new ResourceLocation(string))).value();
    }

    private static NoiseSettings getNoiseSettings(IWorldConfig worldConfig) {
        return NoiseSettings.create(
                worldConfig.getWorldMinY(),
                worldConfig.getWorldHeight(),
                new NoiseSamplingSettings(1.0D, 1.0D, 80.0D, 160.0D),
                new NoiseSlider(-0.078125D, 2, 8),
                new NoiseSlider(0.1171875D, 3, 0),
                1,
                2,
                TerrainProvider.overworld(false));
    }

}

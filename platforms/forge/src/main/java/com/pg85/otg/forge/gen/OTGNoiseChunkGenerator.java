package com.pg85.otg.forge.gen;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pg85.otg.constants.Constants;
import com.pg85.otg.constants.SettingsEnums.CustomStructureType;
import com.pg85.otg.core.OTG;
import com.pg85.otg.core.config.dimensions.DimensionConfig;
import com.pg85.otg.core.config.dimensions.DimensionConfig.OTGDimension;
import com.pg85.otg.core.config.world.WorldConfig;
import com.pg85.otg.core.gen.OTGChunkDecorator;
import com.pg85.otg.core.gen.OTGChunkGenerator;
import com.pg85.otg.core.presets.Preset;
import com.pg85.otg.customobject.structures.CustomStructureCache;
import com.pg85.otg.exceptions.InvalidConfigException;
import com.pg85.otg.forge.presets.ForgePresetLoader;
import com.pg85.otg.forge.biome.ForgeBiome;
import com.pg85.otg.forge.biome.OTGBiomeProvider;
import com.pg85.otg.interfaces.*;
import com.pg85.otg.util.ChunkCoordinate;
import com.pg85.otg.util.gen.ChunkBuffer;
import com.pg85.otg.util.gen.JigsawStructureData;
import com.pg85.otg.util.logging.LogCategory;
import com.pg85.otg.util.logging.LogLevel;
import com.pg85.otg.util.materials.LocalMaterialData;

import com.pg85.otg.util.minecraft.StructureType;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArraySet;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.CrashReport;
import net.minecraft.ReportedException;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.data.worldgen.*;
import net.minecraft.resources.RegistryLookupCodec;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.biome.Climate.Sampler;
import net.minecraft.world.level.biome.Climate.TargetPoint;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.GenerationStep.Carving;
import net.minecraft.world.level.levelgen.Heightmap.Types;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.feature.StructureFeature;
import net.minecraft.world.level.levelgen.feature.configurations.StrongholdConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.StructureFeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.structures.JigsawJunction;
import net.minecraft.world.level.levelgen.feature.structures.StructureTemplatePool.Projection;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.registries.ForgeRegistries;

public final class OTGNoiseChunkGenerator extends ChunkGenerator
{
	// Create a codec to serialise/deserialise OTGNoiseChunkGenerator
	public static final Codec<OTGNoiseChunkGenerator> CODEC = RecordCodecBuilder.create(
		(builder) ->
			builder
				.group(
					Codec.STRING.fieldOf("preset_folder_name").forGetter(
						p -> p.preset.getFolderName()
					),
					Codec.STRING.fieldOf("dim_config_name").forGetter(
						p -> p.dimConfigName
					),
					BiomeSource.CODEC.fieldOf("biome_source").forGetter(
						p -> p.biomeSource
					),
					RegistryOps.retrieveRegistry(Registry.STRUCTURE_SET_REGISTRY).forGetter(
						p -> p.structureSets
					),
					RegistryOps.retrieveRegistry(Registry.NOISE_REGISTRY).forGetter(
						p -> p.noiseRegistry
					),
					Codec.LONG.fieldOf("seed").stable().forGetter(
						p -> p.seed
					),
					NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(
						p -> p.generatorSettingsHolder
					)
				).apply(
					builder, builder.stable(OTGNoiseChunkGenerator::new)
				)
	);

	private final Holder<NoiseGeneratorSettings> generatorSettingsHolder;
	private final int noiseHeight;
	//private final SurfaceNoise surfaceNoise;
	//protected final WorldgenRandom random;

	private final ShadowChunkGenerator shadowChunkGenerator;
	private final OTGChunkGenerator internalGenerator;
	private final OTGChunkDecorator chunkDecorator;
	private final BlockState defaultBlock;
	private final BlockState defaultFluid;
	private final Registry<NormalNoise.NoiseParameters> noiseRegistry;
	private final long seed;
	private final Preset preset;
	private final String dimConfigName;
	private final DimensionConfig dimConfig;
	private final HashMap<StructureType, StructurePlacement> structurePlacementSettings;
	private CustomStructureCache structureCache; // TODO: Move this?
	
	// TODO: Modpack config specific, move this?
	private boolean portalDataProcessed = false;
	private List<LocalMaterialData> portalBlocks;
	private String portalColor;
	private String portalMob;
	private String portalIgnitionSource;

	public OTGNoiseChunkGenerator(BiomeSource biomeProvider, long seed, Registry<StructureSet> structureSetRegistry, Registry<NormalNoise.NoiseParameters> noiseRegistry, Holder<NoiseGeneratorSettings> dimensionSettingsSupplier)
	{
		this(OTG.getEngine().getPresetLoader().getDefaultPresetFolderName(), null, biomeProvider, biomeProvider, structureSetRegistry, noiseRegistry, seed, dimensionSettingsSupplier);
	}

	public OTGNoiseChunkGenerator(String presetFolderName, String dimConfigName, BiomeSource biomeSource, Registry<StructureSet> structureSetRegistry, Registry<NormalNoise.NoiseParameters> noiseRegistry, long seed, Holder<NoiseGeneratorSettings> dimensionSettingsSupplier)
	{
		this(presetFolderName, dimConfigName, biomeSource, biomeSource, structureSetRegistry, noiseRegistry, seed, dimensionSettingsSupplier);
	}

	// TODO: Why are there 2 biome providers, and why does getBiomeProvider() return the second, while we're using the first?
	// It looks like vanilla just inserts the same biomeprovider twice?
	// Vanilla has two biome sources, where the first is population and the second is runtime. Don't know the practical difference this makes.
	//@SuppressWarnings("deprecation")
	private OTGNoiseChunkGenerator(String presetFolderName, String dimConfigName, BiomeSource biomePopulationSource, BiomeSource biomeRuntimeSource, Registry<StructureSet> structureSetRegistry, Registry<NormalNoise.NoiseParameters> noiseRegistry, long seed, Holder<NoiseGeneratorSettings> dimensionSettingsHolder)
	{
		super(structureSetRegistry, Optional.of(getEnabledStructures(presetFolderName)), biomePopulationSource, biomeRuntimeSource, seed);

		if (!(biomePopulationSource instanceof ILayerSource))
		{
			throw new RuntimeException("OTG has detected an incompatible biome provider- try using otg:otg as the biome source name");
		}

		this.noiseRegistry = noiseRegistry;
		this.seed = seed;
		this.preset = OTG.getEngine().getPresetLoader().getPresetByFolderName(presetFolderName);
		if(dimConfigName != null && dimConfigName.trim().length() > 0)
		{
			this.dimConfigName = dimConfigName;
			this.dimConfig = DimensionConfig.fromDisk(this.dimConfigName);
		} else {
			this.dimConfigName = "";
			this.dimConfig = null;
		}
		this.generatorSettingsHolder = dimensionSettingsHolder;
		NoiseGeneratorSettings genSettings = dimensionSettingsHolder.value();
		NoiseSettings noisesettings = genSettings.noiseSettings();
		this.defaultBlock = genSettings.defaultBlock();
		this.defaultFluid = genSettings.defaultFluid();
		this.noiseHeight = noisesettings.height();
		this.structurePlacementSettings = getStructurePlacementMap(preset.getWorldConfig());
		this.shadowChunkGenerator = new ShadowChunkGenerator(OTG.getEngine().getPluginConfig().getMaxWorkerThreads());
		this.internalGenerator = new OTGChunkGenerator(this.preset, seed, (ILayerSource) biomePopulationSource,((ForgePresetLoader)OTG.getEngine().getPresetLoader()).getGlobalIdMapping(presetFolderName), OTG.getEngine().getLogger());
		this.chunkDecorator = new OTGChunkDecorator();
	}
	
	// Structure settings

	// Method to remove structures which have been disabled in the world config
	// TODO: This only handles which structures can exist in the world, *not* what structures spawn
	// 	in certain biomes. In 1.18.2 and on, it seems we need to define this with biome tags.
	// 	From what I can see, we need to have preset makers do this with datapacks. Should be doable.
	private static HolderSet<StructureSet> getEnabledStructures(String presetFolderName)
	{
		Preset preset = OTG.getEngine().getPresetLoader().getPresetByFolderName(presetFolderName);
		IWorldConfig worldConfig = preset.getWorldConfig();
		List<Holder<StructureSet>> holderList = new ArrayList<>();
		HashMap<StructureType, StructurePlacement> placementSettings = getStructurePlacementMap(worldConfig);

		if(worldConfig.getRareBuildingsEnabled())
		{
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.IGLOO, placementSettings.get(StructureType.IGLOO))));
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.SWAMP_HUT, placementSettings.get(StructureType.SWAMP_HUT))));
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.DESERT_PYRAMID, placementSettings.get(StructureType.DESERT_PYRAMID))));
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.JUNGLE_TEMPLE, placementSettings.get(StructureType.JUNGLE_TEMPLE))));
		}

		if(worldConfig.getVillagesEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureSets.VILLAGES.value().structures(), placementSettings.get(StructureType.VILLAGE))));
		if(worldConfig.getPillagerOutpostsEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.PILLAGER_OUTPOST, placementSettings.get(StructureType.PILLLAGER_OUTPOST))));
		if(worldConfig.getStrongholdsEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.STRONGHOLD, placementSettings.get(StructureType.STRONGHOLD))));
		if(worldConfig.getOceanMonumentsEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.OCEAN_MONUMENT, placementSettings.get(StructureType.OCEAN_MONUMENT))));
		if(worldConfig.getEndCitiesEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.END_CITY, placementSettings.get(StructureType.END_CITY))));
		if(worldConfig.getWoodlandMansionsEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.WOODLAND_MANSION, placementSettings.get(StructureType.WOODLAND_MANSION))));
		if(worldConfig.getBuriedTreasureEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.BURIED_TREASURE, placementSettings.get(StructureType.BURIED_TREASURE))));
		if(worldConfig.getMineshaftsEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureSets.MINESHAFTS.value().structures(), placementSettings.get(StructureType.MINESHAFT))));
		if(worldConfig.getRuinedPortalsEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureSets.RUINED_PORTALS.value().structures(), placementSettings.get(StructureType.RUINED_PORTAL))));
		if(worldConfig.getShipWrecksEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureSets.SHIPWRECKS.value().structures(), placementSettings.get(StructureType.SHIPWRECK))));
		if(worldConfig.getOceanRuinsEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureSets.OCEAN_RUINS.value().structures(), placementSettings.get(StructureType.OCEAN_RUINS))));
		if(worldConfig.getBastionRemnantsEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.BASTION_REMNANT, placementSettings.get(StructureType.BASTION_REMNANT))));
		if(worldConfig.getNetherFortressesEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.FORTRESS, placementSettings.get(StructureType.FORTRESS))));
		if(worldConfig.getNetherFossilsEnabled())
			holderList.add(Holder.direct(new StructureSet(StructureFeatures.NETHER_FOSSIL, placementSettings.get(StructureType.NETHER_FOSSIL))));
		return HolderSet.direct(holderList);
	}

	private static HashMap<StructureType, StructurePlacement> getStructurePlacementMap(IWorldConfig worldConfig)
	{
		HashMap<StructureType, StructurePlacement> placementSettings =  new HashMap<>();
		placementSettings.put(StructureType.SWAMP_HUT, new RandomSpreadStructurePlacement(worldConfig.getSwampHutSpacing(), worldConfig.getSwampHutSeparation(), RandomSpreadType.LINEAR, 14357620));
		placementSettings.put(StructureType.IGLOO, new RandomSpreadStructurePlacement(worldConfig.getIglooSpacing(), worldConfig.getIglooSeparation(), RandomSpreadType.LINEAR, 14357618));
		placementSettings.put(StructureType.DESERT_PYRAMID, new RandomSpreadStructurePlacement(worldConfig.getDesertPyramidSpacing(), worldConfig.getDesertPyramidSeparation(), RandomSpreadType.LINEAR, 14357617));
		placementSettings.put(StructureType.JUNGLE_TEMPLE, new RandomSpreadStructurePlacement(worldConfig.getJungleTempleSpacing(), worldConfig.getJungleTempleSeparation(), RandomSpreadType.LINEAR, 14357619));
		placementSettings.put(StructureType.VILLAGE, new RandomSpreadStructurePlacement(worldConfig.getVillageSpacing(), worldConfig.getVillageSeparation(), RandomSpreadType.LINEAR, 10387312));
		placementSettings.put(StructureType.PILLLAGER_OUTPOST, new RandomSpreadStructurePlacement(worldConfig.getPillagerOutpostSpacing(), worldConfig.getPillagerOutpostSeparation(), RandomSpreadType.LINEAR, 165745296));
		placementSettings.put(StructureType.STRONGHOLD, new ConcentricRingsStructurePlacement(worldConfig.getStrongHoldDistance(), worldConfig.getStrongHoldSpread(), worldConfig.getStrongHoldCount()));
		placementSettings.put(StructureType.OCEAN_MONUMENT, new RandomSpreadStructurePlacement(worldConfig.getOceanMonumentSpacing(), worldConfig.getOceanMonumentSeparation(), RandomSpreadType.TRIANGULAR, 10387313));
		placementSettings.put(StructureType.END_CITY, new RandomSpreadStructurePlacement(worldConfig.getEndCitySpacing(), worldConfig.getEndCitySeparation(), RandomSpreadType.TRIANGULAR, 10387313));
		placementSettings.put(StructureType.WOODLAND_MANSION, new RandomSpreadStructurePlacement(worldConfig.getWoodlandMansionSpacing(), worldConfig.getWoodlandMansionSeparation(), RandomSpreadType.TRIANGULAR, 10387319));
		placementSettings.put(StructureType.BURIED_TREASURE, new RandomSpreadStructurePlacement(worldConfig.getBuriedTreasureSpacing(), worldConfig.getBuriedTreasureSeparation(), RandomSpreadType.LINEAR, 0, new Vec3i(9, 0, 9)));
		placementSettings.put(StructureType.MINESHAFT, new RandomSpreadStructurePlacement(worldConfig.getMineshaftSpacing(), worldConfig.getMineshaftSeparation(), RandomSpreadType.LINEAR, 0));
		placementSettings.put(StructureType.RUINED_PORTAL, new RandomSpreadStructurePlacement(worldConfig.getRuinedPortalSpacing(), worldConfig.getRuinedPortalSeparation(), RandomSpreadType.LINEAR, 34222645));
		placementSettings.put(StructureType.SHIPWRECK, new RandomSpreadStructurePlacement(worldConfig.getShipwreckSpacing(), worldConfig.getShipwreckSeparation(), RandomSpreadType.LINEAR, 165745295));
		placementSettings.put(StructureType.OCEAN_RUINS, new RandomSpreadStructurePlacement(worldConfig.getOceanRuinSpacing(), worldConfig.getOceanRuinSeparation(), RandomSpreadType.LINEAR, 14357621));
		placementSettings.put(StructureType.BASTION_REMNANT, new RandomSpreadStructurePlacement(worldConfig.getBastionRemnantSpacing(), worldConfig.getBastionRemnantSeparation(), RandomSpreadType.LINEAR, 30084232));
		placementSettings.put(StructureType.FORTRESS, new RandomSpreadStructurePlacement(worldConfig.getNetherFortressSpacing(), worldConfig.getNetherFortressSeparation(), RandomSpreadType.LINEAR, 30084232));
		placementSettings.put(StructureType.NETHER_FOSSIL, new RandomSpreadStructurePlacement(worldConfig.getNetherFossilSpacing(), worldConfig.getNetherFossilSeparation(), RandomSpreadType.LINEAR, 14357921));
		return placementSettings;
	}
	
	public ICachedBiomeProvider getCachedBiomeProvider()
	{
		return this.internalGenerator.getCachedBiomeProvider();
	}

	public void saveStructureCache()
	{
		if (this.chunkDecorator.getIsSaveRequired() && this.structureCache != null)
		{
			this.structureCache.saveToDisk(OTG.getEngine().getLogger(), this.chunkDecorator);
		}
	}

	public Preset getPreset()
	{
		return this.preset;
	}

	@OnlyIn(Dist.CLIENT)
	@Override
	public ChunkGenerator withSeed(long seed)
	{
		return new OTGNoiseChunkGenerator(this.preset.getFolderName(), this.dimConfigName, this.biomeSource.withSeed(seed), this.structureSets, this.noiseRegistry, seed, this.generatorSettingsHolder);
	}
	
	// Base terrain gen
	@Override
	public CompletableFuture<ChunkAccess> fillFromNoise(Executor executor, Blender blender, StructureFeatureManager accessor, ChunkAccess chunk)
	{
		buildNoise(accessor, chunk);

		return CompletableFuture.completedFuture(chunk);
	}
	
	// Generates the base terrain for a chunk.
	public void buildNoise(StructureFeatureManager manager, ChunkAccess chunk)
	{
		ChunkCoordinate chunkCoord = ChunkCoordinate.fromChunkCoords(chunk.getPos().x, chunk.getPos().z);

		// Dummy random, as we can't get the level random right now
		Random random = new Random();
		// Fetch any chunks that are cached in the WorldGenRegion, so we can
		// pre-emptively generate and cache base terrain for them asynchronously.
		this.shadowChunkGenerator.queueChunksForWorkerThreads((WorldGenRegion)chunk.getWorldForge(), manager, chunk, this, (OTGBiomeProvider)this.biomeSource, this.internalGenerator, this.getSettings(), this.preset.getWorldConfig().getWorldHeightCap());
		
		// If we've already (shadow-)generated and cached this	
		// chunk while it was unloaded, use cached data.
		ChunkBuffer buffer = new ForgeChunkBuffer((ProtoChunk) chunk);
		ChunkAccess cachedChunk = this.shadowChunkGenerator.getChunkWithWait(chunkCoord);
		if (cachedChunk != null)
		{
			this.shadowChunkGenerator.fillWorldGenChunkFromShadowChunk(chunk, cachedChunk);
		} else {			
			// Setup jigsaw data
			ObjectList<JigsawStructureData> structures = new ObjectArrayList<>(10);
			ObjectList<JigsawStructureData> junctions = new ObjectArrayList<>(32);
			ChunkPos pos = chunk.getPos();
			int chunkX = pos.x;
			int chunkZ = pos.z;
			int startX = chunkX << 4;
			int startZ = chunkZ << 4;

			// Iterate through all of the jigsaw structures (villages, pillager outposts, nether fossils)
			for(StructureFeature<?> structure : StructureFeature.NOISE_AFFECTING_FEATURES)
			{
				// Get all structure starts in this chunk
				manager.startsForFeature(SectionPos.of(pos, 0), structure).forEach((start) ->
				{
					// Iterate through the pieces in the structure
					for(StructurePiece piece : start.getPieces())
					{
						// Check if it intersects with this chunk
						if (piece.isCloseToChunk(pos, 12))
						{
							BoundingBox box = piece.getBoundingBox();
							if (piece instanceof PoolElementStructurePiece)
							{
								PoolElementStructurePiece villagePiece = (PoolElementStructurePiece) piece;
								// Add to the list if it's a rigid piece
								if (villagePiece.getElement().getProjection() == Projection.RIGID)
								{
									structures.add(new JigsawStructureData(box.minX(), box.minY(), box.minZ(), box.maxX(), villagePiece.getGroundLevelDelta(), box.maxZ(), true, 0, 0, 0));
								}

								// Get all the junctions in this piece
								for(JigsawJunction junction : villagePiece.getJunctions())
								{
									int sourceX = junction.getSourceX();
									int sourceZ = junction.getSourceZ();

									// If the junction is in this chunk, then add to list
									if (sourceX > startX - 12 && sourceZ > startZ - 12 && sourceX < startX + 15 + 12 && sourceZ < startZ + 15 + 12)
									{
										junctions.add(new JigsawStructureData(0, 0, 0,0, 0, 0, false, junction.getSourceX(), junction.getSourceGroundY(), junction.getSourceZ()));
									}
								}
							} else {
								structures.add(new JigsawStructureData(box.minX(), box.minY(), box.minZ(),box.maxX(), 0, box.maxZ(), false, 0, 0, 0));
							}
						}
					}
				});
			}		
			this.internalGenerator.populateNoise(this.preset.getWorldConfig().getWorldHeightCap(), random, buffer, buffer.getChunkCoordinate(), structures, junctions);
			this.shadowChunkGenerator.setChunkGenerated(chunkCoord);
		}
	}
	
	// Replaces surface and ground blocks in base terrain and places bedrock.
	@Override
	public void buildSurface(WorldGenRegion worldGenRegion, StructureFeatureManager structureFeatureManager, ChunkAccess chunk)
	{
		// OTG handles surface/ground blocks during base terrain gen. For non-OTG biomes used
		// with TemplateForBiome, we want to use registered surfacebuilders though.

		/*
		ChunkPos chunkpos = chunk.getPos();
		int i = chunkpos.x;
		int j = chunkpos.z;
		WorldgenRandom sharedseedrandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.seedUniquifier()));
		sharedseedrandom.setBaseChunkSeed(i, j);
		ChunkPos chunkpos1 = chunk.getPos();
		int chunkMinX = chunkpos1.getMinBlockX();
		int chunkMinZ = chunkpos1.getMinBlockZ();
		int worldX;
		int worldZ;
		int i2;
		double d1;
		IBiome[] biomesForChunk = this.internalGenerator.getCachedBiomeProvider().getBiomesForChunk(ChunkCoordinate.fromBlockCoords(chunkMinX, chunkMinZ));
		IBiome biome;
		
		for(int xInChunk = 0; xInChunk < Constants.CHUNK_SIZE; ++xInChunk)
		{
			for(int zInChunk = 0; zInChunk < Constants.CHUNK_SIZE; ++zInChunk)
			{
				worldX = chunkMinX + xInChunk;
				worldZ = chunkMinZ + zInChunk;
				biome = biomesForChunk[xInChunk * Constants.CHUNK_SIZE + zInChunk];
				if(biome.getBiomeConfig().getIsTemplateForBiome())
				{
					i2 = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG, xInChunk, zInChunk) + 1;
					d1 = this.surfaceNoise.getSurfaceNoiseValue((double)worldX * 0.0625D, (double)worldZ * 0.0625D, 0.0625D, (double)xInChunk * 0.0625D) * 15.0D;
					((ForgeBiome)biome).getBiomeBase().buildSurfaceAt(sharedseedrandom, chunk, worldX, worldZ, i2, d1, ((ForgeMaterialData)biome.getBiomeConfig().getDefaultStoneBlock()).internalBlock(), ((ForgeMaterialData)biome.getBiomeConfig().getDefaultWaterBlock()).internalBlock(), this.getSeaLevel(), 50, worldGenRegion.getSeed());
				}
			}
		}
		*/
		// Skip bedrock, OTG always handles that.
	}

	// Carvers: Caves and ravines
	
	@Override
	public void applyCarvers(WorldGenRegion p_187691_, long seed, BiomeManager biomeManager, StructureFeatureManager structureFeatureManager, ChunkAccess chunk, Carving stage)
	{
		// OTG has its own caves and canyons carvers. We register default carvers to OTG biomes,
		// then check if they have been overridden by mods before using our own carvers.

		if (stage == GenerationStep.Carving.AIR)
		{
			ForgeBiome biome = (ForgeBiome)this.getCachedBiomeProvider().getNoiseBiome(chunk.getPos().x << 2, chunk.getPos().z << 2);
			BiomeGenerationSettings biomegenerationsettings = biome.getBiomeBase().getGenerationSettings();
			List<Supplier<ConfiguredWorldCarver<?>>> list = biomegenerationsettings.getCarvers(stage);

			// Only use OTG carvers when default mc carvers are found
			List<String> defaultCaves = Arrays.asList("minecraft:cave", "minecraft:underwater_cave", "minecraft:nether_cave");			
			boolean cavesEnabled = this.preset.getWorldConfig().getCavesEnabled() && list.stream().anyMatch(
				a -> defaultCaves.stream().anyMatch(
					b -> b.equals(
						ForgeRegistries.WORLD_CARVERS.getKey(a.get().worldCarver).toString()
					)
				)
			);
			List<String> defaultRavines = Arrays.asList("minecraft:canyon", "minecraft:underwater_canyon");
			boolean ravinesEnabled = this.preset.getWorldConfig().getRavinesEnabled() && list.stream().anyMatch(
				a -> defaultRavines.stream().anyMatch(
					b -> b.equals(
						ForgeRegistries.WORLD_CARVERS.getKey(a.get().worldCarver).toString()
					)
				)
			);
			if(cavesEnabled || ravinesEnabled)
			{
				ProtoChunk protoChunk = (ProtoChunk) chunk;
				ChunkBuffer chunkBuffer = new ForgeChunkBuffer(protoChunk);

				// CarvingMask is a platform dependent class, so we need to reflect to get the underlying bitset.
				// TODO: maybe would be better to use a new class that contains set and get lambdas?
				CarvingMask carvingMaskRaw = protoChunk.getOrCreateCarvingMask(stage);
				try {
					Field theRealMask = carvingMaskRaw.getClass().getDeclaredField("mask");
					theRealMask.setAccessible(true);
					BitSet carvingMask = (BitSet)theRealMask.get(carvingMaskRaw);
					this.internalGenerator.carve(chunkBuffer, seed, protoChunk.getPos().x, protoChunk.getPos().z, carvingMask, cavesEnabled, ravinesEnabled);
				} catch (NoSuchFieldException e) {
					if (OTG.getEngine().getLogger().getLogCategoryEnabled(LogCategory.MAIN)) {
						OTG.getEngine().getLogger().log(LogLevel.ERROR, LogCategory.MAIN, "!!! Error obtaining the carving mask! Caves will not generate! Stacktrace:\n" + e.getStackTrace());
					}
				} catch (IllegalAccessException e) {
					if (OTG.getEngine().getLogger().getLogCategoryEnabled(LogCategory.MAIN)) {
						OTG.getEngine().getLogger().log(LogLevel.ERROR, LogCategory.MAIN, "!!! Error obtaining the carving mask! Caves will not generate! Stacktrace:\n" + e.getStackTrace());
					}
				}
			}
		}
		applyNonOTGCarvers(seed, biomeManager, chunk, stage);

	}

	public void applyNonOTGCarvers(long seed, BiomeManager biomeManager, ChunkAccess chunk, GenerationStep.Carving stage)
	{
		/*
		BiomeManager biomemanager = biomeManager.withDifferentSource(this.biomeSource);
		WorldgenRandom sharedseedrandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.seedUniquifier()));
		CarvingContext carvingcontext = new CarvingContext(this, chunk);
		ChunkPos chunkpos = chunk.getPos();
		Aquifer aquifer = this.createAquifer(chunk);
		int j = chunkpos.x;
		int k = chunkpos.z;
		ForgeBiome biome = (ForgeBiome)this.getCachedBiomeProvider().getNoiseBiome(chunk.getPos().x << 2, chunk.getPos().z << 2);
		BiomeGenerationSettings biomegenerationsettings = biome.getBiomeBase().getGenerationSettings();
		BitSet bitset = ((ProtoChunk)chunk).getOrCreateCarvingMask(stage);

		List<String> defaultCavesAndRavines = Arrays.asList("minecraft:cave", "minecraft:underwater_cave", "minecraft:nether_cave", "minecraft:canyon", "minecraft:underwater_canyon");					
		for(int l = j - 8; l <= j + 8; ++l)
		{
			for(int i1 = k - 8; i1 <= k + 8; ++i1)
			{
				ChunkPos chunkpos1 = new ChunkPos(chunkpos.x + j, chunkpos.z + k);
				List<Supplier<ConfiguredWorldCarver<?>>> list = biomegenerationsettings.getCarvers(stage);
				ListIterator<Supplier<ConfiguredWorldCarver<?>>> listiterator = list.listIterator();
				while(listiterator.hasNext())
				{
					int j1 = listiterator.nextIndex();
					ConfiguredWorldCarver<?> configuredcarver = listiterator.next().get();
					String carverRegistryName = ForgeRegistries.WORLD_CARVERS.getKey(configuredcarver.worldCarver).toString();
					// OTG uses its own caves and canyon carvers, ignore the default ones.
					if(defaultCavesAndRavines.stream().noneMatch(a -> a.equals(carverRegistryName)))
					{
						sharedseedrandom.setLargeFeatureSeed(seed + (long)j1, l, i1);
						if (configuredcarver.isStartChunk(sharedseedrandom))
						{
							configuredcarver.carve(carvingcontext, chunk, biomemanager::getBiome, sharedseedrandom, aquifer, chunkpos1, bitset);
						}
					}
				}
			}
		}
		*/
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel worldGenLevel, ChunkAccess p_187713_, StructureFeatureManager p_187714_)
	{
		if(!OTG.getEngine().getPluginConfig().getDecorationEnabled())
		{
			return;
		}
		
		ChunkPos chunkpos = p_187713_.getPos();
		if (!SharedConstants.debugVoidTerrain(chunkpos))
		{
			WorldGenRegion worldGenRegion = ((WorldGenRegion)worldGenLevel);
			SectionPos sectionpos = SectionPos.of(chunkpos, worldGenRegion.getMinSection());
			BlockPos blockpos = sectionpos.origin();
			Map<Integer, List<StructureFeature<?>>> map = Registry.STRUCTURE_FEATURE.stream().collect(Collectors.groupingBy((p_187720_) -> {
				return p_187720_.step().ordinal();
			}));
			List<BiomeSource.StepFeatureData> list = this.biomeSource.featuresPerStep();
			WorldgenRandom worldgenrandom = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.seedUniquifier()));
			long i = worldgenrandom.setDecorationSeed(worldGenRegion.getSeed(), blockpos.getX(), blockpos.getZ());
			
			int worldX = worldGenRegion.getCenter().x * Constants.CHUNK_SIZE;
			int worldZ =worldGenRegion.getCenter().z * Constants.CHUNK_SIZE;
			ChunkCoordinate chunkBeingDecorated = ChunkCoordinate.fromBlockCoords(worldX, worldZ);
			IBiome biome = this.internalGenerator.getCachedBiomeProvider().getNoiseBiome((worldGenRegion.getCenter().x << 2) + 2, (worldGenRegion.getCenter().z << 2) + 2);
			IBiomeConfig biomeConfig = biome.getBiomeConfig();
			ForgeWorldGenRegion forgeWorldGenRegion = new ForgeWorldGenRegion(this.preset.getFolderName(), this.preset.getWorldConfig(), worldGenRegion, this);
			// World save folder name may not be identical to level name, fetch it.
			Path worldSaveFolder = worldGenRegion.getLevel().getServer().getWorldPath(LevelResource.PLAYER_DATA_DIR).getParent();

			try {
				this.chunkDecorator.decorate(this.preset.getFolderName(), chunkBeingDecorated, forgeWorldGenRegion, biome.getBiomeConfig(), getStructureCache(worldSaveFolder));
			}
			catch (Exception exception)
			{
				CrashReport crashreport = CrashReport.forThrowable(exception, "Biome decoration");
				crashreport.addCategory("Generation").setDetail("CenterX", worldX).setDetail("CenterZ", worldZ).setDetail("Seed", seed);
				throw new ReportedException(crashreport);
			}

			Set<Biome> set = new ObjectArraySet<>();
			//if (this instanceof FlatLevelSource)
			//{
				set.addAll(this.biomeSource.possibleBiomes());
			//} else {
				/* Behold, the future
				ChunkPos.rangeClosed(sectionpos.chunk(), 1).forEach((p_196730_) -> {
					ChunkAccess chunkaccess = p_187712_.getChunk(p_196730_.x, p_196730_.z);
		
					for(LevelChunkSection levelchunksection : chunkaccess.getSections())
					{
						levelchunksection.getBiomes().getAll(set::add);
					}
				});
				set.retainAll(this.biomeSource.possibleBiomes());
				*/
			//}
		
			int j = list.size();
		
			try {
				Registry<PlacedFeature> registry = worldGenRegion.registryAccess().registryOrThrow(Registry.PLACED_FEATURE_REGISTRY);
				Registry<StructureFeature<?>> registry1 = worldGenRegion.registryAccess().registryOrThrow(Registry.STRUCTURE_FEATURE_REGISTRY);
				int k = Math.max(GenerationStep.Decoration.values().length, j);
			
				for(int l = 0; l < k; ++l)
				{
					int i1 = 0;
					if (p_187714_.shouldGenerateFeatures())
					{
						for(StructureFeature<?> structurefeature : map.getOrDefault(l, Collections.emptyList()))
						{
							worldgenrandom.setFeatureSeed(i, i1, l);
							Supplier<String> supplier = () -> {
								return registry1.getResourceKey(structurefeature).map(Object::toString).orElseGet(structurefeature::toString);
							};
					
							try {
								worldGenRegion.setCurrentlyGenerating(supplier);
								p_187714_.startsForFeature(sectionpos, structurefeature).forEach((p_196726_) -> {
									p_196726_.placeInChunk(worldGenRegion, p_187714_, this, worldgenrandom, getWritableArea(p_187713_), chunkpos);
								});
							} catch (Exception exception) {
								CrashReport crashreport1 = CrashReport.forThrowable(exception, "Feature placement");
								crashreport1.addCategory("Feature").setDetail("Description", supplier::get);
								throw new ReportedException(crashreport1);
							}
					
							++i1;
						}
					}
					
					if (l < j)
					{
						IntSet intset = new IntArraySet();
					
						for(Biome biome2x : set)
						{
							List<List<Supplier<PlacedFeature>>> list2 = biome2x.getGenerationSettings().features();
							if (l < list2.size())
							{
								List<Supplier<PlacedFeature>> list1 = list2.get(l);
								BiomeSource.StepFeatureData biomesource$stepfeaturedata1 = list.get(l);
								list1.stream().map(Supplier::get).forEach((p_196751_) -> {
									intset.add(biomesource$stepfeaturedata1.indexMapping().applyAsInt(p_196751_));
								});
							}
						}
						
						int j1 = intset.size();
						int[] aint = intset.toIntArray();
						Arrays.sort(aint);
						BiomeSource.StepFeatureData biomesource$stepfeaturedata = list.get(l);
						
						for(int k1 = 0; k1 < j1; ++k1)
						{
							int l1 = aint[k1];
							PlacedFeature placedfeature = biomesource$stepfeaturedata.features().get(l1);
							Supplier<String> supplier1 = () -> {
								return registry.getResourceKey(placedfeature).map(Object::toString).orElseGet(placedfeature::toString);
							};
							worldgenrandom.setFeatureSeed(i, l1, l);
							
							try {
								worldGenRegion.setCurrentlyGenerating(supplier1);
								placedfeature.placeWithBiomeCheck(worldGenRegion, this, worldgenrandom, blockpos);
							} catch (Exception exception1) {
								CrashReport crashreport2 = CrashReport.forThrowable(exception1, "Feature placement");
								crashreport2.addCategory("Feature").setDetail("Description", supplier1::get);
								throw new ReportedException(crashreport2);
							}
						}
					}
				}
				
				worldGenRegion.setCurrentlyGenerating((Supplier<String>)null);
			} catch (Exception exception2) {
				CrashReport crashreport = CrashReport.forThrowable(exception2, "Biome decoration");
				crashreport.addCategory("Generation").setDetail("CenterX", chunkpos.x).setDetail("CenterZ", chunkpos.z).setDetail("Seed", i);
				throw new ReportedException(crashreport);
			}
		}
	}
	
	private static BoundingBox getWritableArea(ChunkAccess p_187718_)
	{
		ChunkPos chunkpos = p_187718_.getPos();
		int i = chunkpos.getMinBlockX();
		int j = chunkpos.getMinBlockZ();
		LevelHeightAccessor levelheightaccessor = p_187718_.getHeightAccessorForGeneration();
		int k = levelheightaccessor.getMinBuildHeight() + 1;
		int l = levelheightaccessor.getMaxBuildHeight() - 1;
		return new BoundingBox(i, k, j, i + 15, l, j + 15);
	}
	
	// Mob spawning on chunk tick
//	@Override
//	public WeightedRandomList<MobSpawnSettings.SpawnerData> getMobsAt(Holder<Biome> biome, StructureFeatureManager structureManager, MobCategory entityClassification, BlockPos blockPos)
//	{
//		if (!structureManager.hasAnyStructureAt(blockPos))
//		{
//			return super.getMobsAt(biome, structureManager, entityClassification, blockPos);
//		} else {
//			WeightedRandomList<MobSpawnSettings.SpawnerData> spawns = net.minecraftforge.common.world.StructureSpawnManager.getStructureSpawns(structureManager, entityClassification, blockPos);
//			if (spawns != null) return spawns;
//			return (entityClassification == MobCategory.UNDERGROUND_WATER_CREATURE || entityClassification == MobCategory.AXOLOTLS)
//					&& structureManager.getStructureAt(blockPos, StructureFeature.OCEAN_MONUMENT).isValid()
//					? MobSpawnSettings.EMPTY_MOB_LIST
//					: super.getMobsAt(biome, structureManager, entityClassification, blockPos);
//		}
//	}
	
	// Mob spawning on initial chunk spawn (animals).
	@SuppressWarnings("deprecation")
	@Override
	public void spawnOriginalMobs(WorldGenRegion worldGenRegion)
	{
		if (!this.generatorSettingsHolder.value().disableMobGeneration())
		{
			int chunkX = worldGenRegion.getCenter().x;
			int chunkZ = worldGenRegion.getCenter().z;
			IBiome biome = this.internalGenerator.getCachedBiomeProvider().getBiome(chunkX * Constants.CHUNK_SIZE, chunkZ * Constants.CHUNK_SIZE);
			WorldgenRandom sharedseedrandom = new WorldgenRandom(new LegacyRandomSource(RandomSupport.seedUniquifier()));
			sharedseedrandom.setDecorationSeed(worldGenRegion.getSeed(), chunkX << 4, chunkZ << 4);
			NaturalSpawner.spawnMobsForChunkGeneration(worldGenRegion, ((ForgeBiome)biome).getHolder(), worldGenRegion.getCenter(), sharedseedrandom);
		}
	}	

	// Noise

	@Override
	public int getBaseHeight(int x, int z, Types heightmapType, LevelHeightAccessor world)
	{
		return this.sampleHeightmap(x, z, null, heightmapType.isOpaque());
	}

	// Provides a sample of the full column for structure generation.
	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor world)
	{
		BlockState[] ablockstate = new BlockState[this.internalGenerator.getNoiseSizeY() * 8];
		this.sampleHeightmap(x, x, ablockstate, null);
		return new NoiseColumn(0, ablockstate);
	}

	@Override
	public void addDebugScreenInfo(List<String> text, BlockPos pos) {
		// TODO: what does this do? -auth
	}

	// Samples the noise at a column and provides a view of the blockstates, or fills a heightmap.
	private int sampleHeightmap(int x, int z, @Nullable BlockState[] blockStates, @Nullable Predicate<BlockState> predicate)
	{
		// Get all of the coordinate starts and positions
		int xStart = Math.floorDiv(x, 4);
		int zStart = Math.floorDiv(z, 4);
		int xProgress = Math.floorMod(x, 4);
		int zProgress = Math.floorMod(z, 4);
		double xLerp = (double) xProgress / 4.0;
		double zLerp = (double) zProgress / 4.0;
		// Create the noise data in a 2 * 2 * 32 grid for interpolation.
		double[][] noiseData = new double[4][this.internalGenerator.getNoiseSizeY() + 1];

		// Initialize noise array.
		for (int i = 0; i < noiseData.length; i++)
		{
			noiseData[i] = new double[this.internalGenerator.getNoiseSizeY() + 1];
		}

		// Sample all 4 nearby columns.
		this.internalGenerator.getNoiseColumn(noiseData[0], xStart, zStart);
		this.internalGenerator.getNoiseColumn(noiseData[1], xStart, zStart + 1);
		this.internalGenerator.getNoiseColumn(noiseData[2], xStart + 1, zStart);
		this.internalGenerator.getNoiseColumn(noiseData[3], xStart + 1, zStart + 1);

		// [0, 32] -> noise chunks
		for (int noiseY = this.internalGenerator.getNoiseSizeY() - 1; noiseY >= 0; --noiseY)
		{
			// Gets all the noise in a 2x2x2 cube and interpolates it together.
			// Lower pieces
			double x0z0y0 = noiseData[0][noiseY];
			double x0z1y0 = noiseData[1][noiseY];
			double x1z0y0 = noiseData[2][noiseY];
			double x1z1y0 = noiseData[3][noiseY];
			// Upper pieces
			double x0z0y1 = noiseData[0][noiseY + 1];
			double x0z1y1 = noiseData[1][noiseY + 1];
			double x1z0y1 = noiseData[2][noiseY + 1];
			double x1z1y1 = noiseData[3][noiseY + 1];

			// [0, 8] -> noise pieces
			for (int pieceY = 7; pieceY >= 0; --pieceY)
			{
				double yLerp = (double) pieceY / 8.0;
				// Density at this position given the current y interpolation
				double density = Mth.lerp3(yLerp, xLerp, zLerp, x0z0y0, x0z0y1, x1z0y0, x1z0y1, x0z1y0, x0z1y1, x1z1y0, x1z1y1);

				// Get the real y position (translate noise chunk and noise piece)
				int y = (noiseY * 8) + pieceY;

				BlockState state = this.getBlockState(density, y);
				if (blockStates != null)
				{
					blockStates[y] = state;
				}

				// return y if it fails the check
				if (predicate != null && predicate.test(state))
				{
					return y + 1;
				}
			}
		}

		return 0;
	}

	// MC's NoiseChunkGenerator returns defaultBlock and defaultFluid here, so callers 
	// apparently don't rely on any blocks (re)placed after base terrain gen, only on
	// the default block/liquid set for the dimension (stone/water for overworld, 
	// netherrack/lava for nether), that MC uses for base terrain gen.
	// We can do the same, no need to pass biome config and fetch replaced blocks etc. 
	// OTG does place blocks other than defaultBlock/defaultLiquid during base terrain gen 
	// (for replaceblocks/sagc), but that shouldn't matter for the callers of this method.
	// Actually, it's probably better if they don't see OTG's replaced blocks, and just see
	// the default blocks instead, as vanilla MC would do.
	private BlockState getBlockState(double density, int y)
	{
		if (density > 0.0D)
		{
			return this.defaultBlock;
		}
		else if (y < this.getSeaLevel())
		{
			return this.defaultFluid;
		} else {
			return Blocks.AIR.defaultBlockState();
		}
	}

	// Getters / misc

	@Override
	protected Codec<? extends ChunkGenerator> codec()
	{
		return CODEC;
	}

	@Override
	public int getGenDepth()
	{
		return this.noiseHeight;
	}

	@Override
	public int getSeaLevel()
	{
		return this.generatorSettingsHolder.value().seaLevel();
	}
	
	@Override
	public int getMinY()
	{
		return this.generatorSettingsHolder.value().noiseSettings().minY();
	}	

	public CustomStructureCache getStructureCache(Path worldSaveFolder)
	{
		if(this.structureCache == null)
		{
			this.structureCache = OTG.getEngine().createCustomStructureCache(this.preset.getFolderName(), worldSaveFolder, this.seed, this.preset.getWorldConfig().getCustomStructureType() == CustomStructureType.BO4);
		}
		return this.structureCache;
	}

	double getBiomeBlocksNoiseValue(int blockX, int blockZ)
	{
		return this.internalGenerator.getBiomeBlocksNoiseValue(blockX, blockZ);
	}

	// Shadowgen
	
	public void stopWorkerThreads()
	{
		this.shadowChunkGenerator.stopWorkerThreads();
	}

	public Boolean checkHasVanillaStructureWithoutLoading(ServerLevel world, ChunkCoordinate chunkCoord)
	{
		return this.shadowChunkGenerator.checkHasVanillaStructureWithoutLoading(world, this, (OTGBiomeProvider)this.biomeSource, this.getSettings(), chunkCoord, this.internalGenerator.getCachedBiomeProvider(), false);
	}

	public int getHighestBlockYInUnloadedChunk(Random worldRandom, int x, int z, boolean findSolid, boolean findLiquid, boolean ignoreLiquid, boolean ignoreSnow, ServerLevel level)
	{
		return this.shadowChunkGenerator.getHighestBlockYInUnloadedChunk(this.internalGenerator, this.preset.getWorldConfig().getWorldHeightCap(), worldRandom, x, z, findSolid, findLiquid, ignoreLiquid, ignoreSnow, level);
	}

	public LocalMaterialData getMaterialInUnloadedChunk(Random worldRandom, int x, int y, int z, ServerLevel level)
	{
		return this.shadowChunkGenerator.getMaterialInUnloadedChunk(this.internalGenerator, this.preset.getWorldConfig().getWorldHeightCap(), worldRandom, x, y, z, level);
	}

	public ForgeChunkBuffer getChunkWithoutLoadingOrCaching(Random random, ChunkCoordinate chunkCoord, ServerLevel level)
	{
		return this.shadowChunkGenerator.getChunkWithoutLoadingOrCaching(this.internalGenerator, this.preset.getWorldConfig().getWorldHeightCap(), random, chunkCoord, level);
	}
	
	// Modpack config
	// TODO: Move this?

	public String getPortalColor()
	{
		processDimensionConfigData();
		return this.portalColor;
	}

	public String getPortalMob()
	{
		processDimensionConfigData();
		return this.portalMob;
	}

	public String getPortalIgnitionSource()
	{
		processDimensionConfigData();
		return this.portalIgnitionSource;
	}
		
	public List<LocalMaterialData> getPortalBlocks()
	{
		processDimensionConfigData();
		return this.portalBlocks;
	}

	private void processDimensionConfigData()
	{
		if(!this.portalDataProcessed)
		{
			this.portalDataProcessed = true;
			if(this.dimConfig != null)
			{
				IMaterialReader materialReader = OTG.getEngine().getPresetLoader().getMaterialReader(this.preset.getFolderName());
				for(OTGDimension dim : this.dimConfig.Dimensions)
				{
					if(dim.PresetFolderName != null && this.preset.getFolderName().equals(dim.PresetFolderName))
					{
						if(dim.PortalBlocks != null && dim.PortalBlocks.trim().length() > 0)
						{
							String[] portalBlocks = dim.PortalBlocks.split(",");
							ArrayList<LocalMaterialData> materials = new ArrayList<LocalMaterialData>();					
							for(String materialString : portalBlocks)
							{
								LocalMaterialData material = null;
								try {
									material = materialReader.readMaterial(materialString.trim());
								} catch (InvalidConfigException e) { }
								if(material != null)
								{
									materials.add(material);
								}
							}
							this.portalBlocks = materials;
						}					
						this.portalColor = dim.PortalColor;
						this.portalMob = dim.PortalMob;
						this.portalIgnitionSource = dim.PortalIgnitionSource;
						break;
					}
				}
			}
			if(this.portalBlocks == null || this.portalBlocks.size() == 0)
			{
				this.portalBlocks = this.preset.getWorldConfig().getPortalBlocks(); 
			}
			if(this.portalColor == null)
			{
				this.portalColor = this.preset.getWorldConfig().getPortalColor();	
			}
			if(this.portalMob == null)
			{
				this.portalMob = this.preset.getWorldConfig().getPortalMob();
			}
			if(this.portalIgnitionSource == null)
			{
				this.portalIgnitionSource = this.preset.getWorldConfig().getPortalIgnitionSource();
			}
		}
	}
	
	@Override
	public Sampler climateSampler()
	{
		return Climate.empty();
	}
	
	/** @deprecated */
	@Deprecated
	public Optional<BlockState> topMaterial(CarvingContext p_188669_, Function<BlockPos, Biome> p_188670_, ChunkAccess p_188671_, NoiseChunk p_188672_, BlockPos p_188673_, boolean p_188674_)
	{
		//return this.surfaceSystem.topMaterial(this.settings.get().surfaceRule(), p_188669_, p_188670_, p_188671_, p_188672_, p_188673_, p_188674_);
		return Optional.empty();
	}
}

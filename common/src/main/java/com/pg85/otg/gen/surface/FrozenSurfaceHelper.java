package com.pg85.otg.gen.surface;

import com.pg85.otg.common.LocalBiome;
import com.pg85.otg.common.LocalWorldGenRegion;
import com.pg85.otg.common.materials.LocalMaterialData;
import com.pg85.otg.common.materials.LocalMaterials;
import com.pg85.otg.config.biome.BiomeConfig;
import com.pg85.otg.config.standard.PluginStandardValues;
import com.pg85.otg.config.standard.WorldStandardValues;
import com.pg85.otg.util.ChunkCoordinate;

public class FrozenSurfaceHelper
{
    private final int maxPropagationSize = 15;
    private int currentPropagationSize = 0;
	private int maxLayersOnLeaves = 3;

    public FrozenSurfaceHelper() { }

    /**
     * Freezes and Applied snow to an offset chunkCoordinate
     * @param chunkCoord The chunk to freeze and snow on
     */
    public void freezeChunk(LocalWorldGenRegion worldGenRegion, ChunkCoordinate chunkCoord)
    {
        int x = chunkCoord.getBlockXCenter();
        int z = chunkCoord.getBlockZCenter();
        for (int i = 0; i < ChunkCoordinate.CHUNK_SIZE; i++)
        {
            for (int j = 0; j < ChunkCoordinate.CHUNK_SIZE; j++)
            {
                int blockToFreezeX = x + i;
                int blockToFreezeZ = z + j;
                freezeColumn(worldGenRegion, blockToFreezeX, blockToFreezeZ, chunkCoord);
            }
        }
    }

    /**
     * Performs a liquid freeze and lays down a layer of snow on a Chunk column
     * @param x Location X
     * @param z Location Z
     */
    private void freezeColumn(LocalWorldGenRegion worldGenRegion, int x, int z, ChunkCoordinate chunkBeingPopulated)
    {
        LocalBiome biome = worldGenRegion.getBiomeForPopulation(x, z, chunkBeingPopulated);
        if (biome != null)
        {
            int blockToFreezeY = worldGenRegion.getHighestBlockAboveYAt(x, z, chunkBeingPopulated);
            float tempAtBlockToFreeze = biome.getTemperatureAt(x, blockToFreezeY, z);
            if (blockToFreezeY > 0 && tempAtBlockToFreeze < WorldStandardValues.SNOW_AND_ICE_TEMP)
            {
                this.currentPropagationSize = 0;
                // Start to freeze liquids
                if (!freezeLiquid(worldGenRegion, x, blockToFreezeY -1, z, chunkBeingPopulated))
                {
                    // Snow has to be placed on an empty space on a block that accepts snow in the world
                    startSnowFall(worldGenRegion, x, blockToFreezeY, z, biome, chunkBeingPopulated);
                }
            }
        }
    }

    /**
     * Attempts to freeze liquids at the given location
     * @param x Location X
     * @param y Location Y
     * @param z Location Z
     * @return If a liquid was present at the given location (not necessarily successful in freezing)
     */
    private boolean freezeLiquid(LocalWorldGenRegion worldGenRegion, int x, int y, int z, ChunkCoordinate chunkBeingPopulated)
    {
        BiomeConfig biomeConfig = worldGenRegion.getBiomeConfigForPopulation(x, z, chunkBeingPopulated);
        if (biomeConfig != null)
        {
            LocalMaterialData materialToFreeze = worldGenRegion.getMaterial(x, y, z, chunkBeingPopulated);
            if (materialToFreeze.isLiquid())
            {
            	boolean bFroze = false;
                // Water & Stationary Water => IceBlock
            	LocalMaterialData iceBlock = biomeConfig.getIceBlockReplaced(y);
                if(shouldFreeze(x, y, z, materialToFreeze, iceBlock, LocalMaterials.WATER, chunkBeingPopulated))
                {
                	worldGenRegion.setBlock(x, y, z, iceBlock, null, chunkBeingPopulated, false);
                    bFroze = true;
                } else {
                    LocalMaterialData cooledLavaBlock = biomeConfig.getCooledLavaBlockReplaced(y);
	                // Lava & Stationary Lava => CooledLavaBlock
                	if(shouldFreeze(x, y, z, materialToFreeze, cooledLavaBlock, LocalMaterials.LAVA, chunkBeingPopulated))
	                {
                		worldGenRegion.setBlock(x, y, z, cooledLavaBlock, null, chunkBeingPopulated, true);
	                    bFroze = true;
	                }
                }
                if(bFroze)
                {
                    if (worldGenRegion.getWorldConfig().fullyFreezeLakes && this.currentPropagationSize < this.maxPropagationSize)
                    {
                        this.currentPropagationSize++;
                        propagateFreeze(worldGenRegion, x, y, z, chunkBeingPopulated);
                    }
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Freezes two types of blocks to a third type at a specific location.
     *
     * Example: WATER and STATIONARY_WATER to ICE
     * @param x Location X
     * @param y Location Y
     * @param z Location Z
     * @param thawedMaterial The material to be checked and if passed, frozen
     * @param frozenMaterial The material to freeze the thawed material to if checks pass
     * @param check1 The first material to check for
     * @param check2 The second meterial to check for
     */
    private boolean shouldFreeze(int x, int y, int z, LocalMaterialData thawedMaterial, LocalMaterialData frozenMaterial, LocalMaterialData check1, ChunkCoordinate chunkBeingPopulated)
    {
        return (thawedMaterial.isMaterial(check1) && !frozenMaterial.isMaterial(check1));
    }

    /**
     * Determines all Y locations that need snow, and how much snow in each Y location based on temperature
     * and transparent block pass-through. Sets snow to determined height for each Y location applicable.
     * @param x Location X
     * @param y Location Y
     * @param z Location Z
     * @param biome The biome associated with the chunk column
     */
    private void startSnowFall(LocalWorldGenRegion worldGenRegion, int x, int y, int z, LocalBiome biome, ChunkCoordinate chunkBeingPopulated)
    {
        int decreaseFactor = 0;
        BiomeConfig biomeConfig = biome.getBiomeConfig();

        float tempAtBlockToFreeze;
        int snowHeight;
        LocalMaterialData materialToSnowAt = null;
        LocalMaterialData materialToSnowOn = null;
        if(worldGenRegion.getWorldConfig().betterSnowFall)
        {
	        tempAtBlockToFreeze = biome.getTemperatureAt(x, y, z);
	        snowHeight = biomeConfig.getSnowHeight(tempAtBlockToFreeze);
        } else {
        	snowHeight = 0;
        }        
    	while (
			y > PluginStandardValues.WORLD_DEPTH + 1 && 
			decreaseFactor < 8 &&
			snowHeight - decreaseFactor >= 0
		)
        {
            materialToSnowAt = worldGenRegion.getMaterial(x, y, z, chunkBeingPopulated);
            materialToSnowOn = worldGenRegion.getMaterial(x, y - 1, z, chunkBeingPopulated);            
            if (
        		materialToSnowAt != null &&
        		materialToSnowOn != null &&
        		materialToSnowAt.isAir() &&
        		materialToSnowOn.canSnowFallOn()
    		)
            {
            	// If we've spawned all snow layers, exit.
                if(this.setSnowFallAtLocation(worldGenRegion, x, y, z, snowHeight - decreaseFactor, materialToSnowOn, chunkBeingPopulated))
                {
                	break;
                }
                // Spawned on leaves, which can only carry maxLayersOnLeaves snow layers. 
                // We have more snow layers to spawn.
                decreaseFactor += maxLayersOnLeaves;
            }
            if(materialToSnowOn == null || materialToSnowOn.isSolid())
            {
            	break;
            }
            y--;
        }
    }

    /**
     * Applied snow to a location
     * @param x Location X
     * @param y Location Y
     * @param z Location Z
     * @param baseSnowHeight The base height snow should be
     * @param materialToSnowOn The material that might have snow applied
     */
    private boolean setSnowFallAtLocation(LocalWorldGenRegion worldGenRegion, int x, int y, int z, int baseSnowHeight, LocalMaterialData materialToSnowOn, ChunkCoordinate chunkBeingPopulated)
    {
        LocalMaterialData snowMass;
        if (materialToSnowOn.isLeaves())
        {
            // Snow Layer(s) for trees, let each leaf carry maxLayersOnLeaves or less layers of snow,
        	// any remaining layers will fall through.
        	// TODO: Reimplement this when block data works
        	snowMass = LocalMaterials.SNOW;
        	//snowMass = LocalMaterialManager.toLocalMaterialData(LocalMaterialManager.SNOW, baseSnowHeight <= maxLayersOnLeaves - 1 ? baseSnowHeight : maxLayersOnLeaves - 1);
        	worldGenRegion.setBlock(x, y, z, snowMass, null, chunkBeingPopulated, true);
            return baseSnowHeight <= maxLayersOnLeaves - 1;
        }
        
        // Basic Snow Layer(s)
        // TODO: Reimplement this when block data works
        //snowMass = LocalMaterialManager.toLocalMaterialData(DefaultMaterial.SNOW, baseSnowHeight);
        snowMass = LocalMaterials.SNOW;
        worldGenRegion.setBlock(x, y, z, snowMass, null, chunkBeingPopulated, true);
        return true;
    }

    /**
     * Helps propagate the freezing of liquids.
     * @param x Location X
     * @param y Location Y
     * @param z Location Z
     */
    private void propagateFreeze(LocalWorldGenRegion worldGenRegion, int x, int y, int z, ChunkCoordinate chunkBeingPopulated)
    {
        this.propagationHelper(worldGenRegion, x + 1, y, z, chunkBeingPopulated);
        this.propagationHelper(worldGenRegion, x + 1, y, z + 1, chunkBeingPopulated);
        this.propagationHelper(worldGenRegion, x, y, z + 1, chunkBeingPopulated);
        this.propagationHelper(worldGenRegion, x - 1, y, z + 1, chunkBeingPopulated);
        this.propagationHelper(worldGenRegion, x - 1, y, z, chunkBeingPopulated);
        this.propagationHelper(worldGenRegion, x - 1, y, z - 1, chunkBeingPopulated);
        this.propagationHelper(worldGenRegion, x, y, z - 1, chunkBeingPopulated);
        this.propagationHelper(worldGenRegion, x + 1 , y, z - 1, chunkBeingPopulated);
    }

    /**
     * Called by propagateFreeze, does the actual checks and then called freezeLiquid
     * @param x Location X
     * @param y Location Y
     * @param z Location Z
     */
    private void propagationHelper(LocalWorldGenRegion worldGenRegion, int x, int y, int z, ChunkCoordinate chunkBeingPopulated)
    {
        if (worldGenRegion.getHighestBlockAboveYAt(x, z, chunkBeingPopulated) - 1 > y && this.currentPropagationSize < this.maxPropagationSize)
        {
            this.freezeLiquid(worldGenRegion, x, y, z, chunkBeingPopulated);
        }
    }
}

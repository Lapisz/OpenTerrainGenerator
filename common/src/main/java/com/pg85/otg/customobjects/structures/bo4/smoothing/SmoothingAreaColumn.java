package com.pg85.otg.customobjects.structures.bo4.smoothing;

import java.util.ArrayList;

import com.pg85.otg.OTG;
import com.pg85.otg.common.LocalWorldGenRegion;
import com.pg85.otg.common.materials.LocalMaterialData;
import com.pg85.otg.common.materials.LocalMaterials;
import com.pg85.otg.config.biome.BiomeConfig;
import com.pg85.otg.customobjects.bo4.BO4Config;
import com.pg85.otg.customobjects.structures.bo4.smoothing.SmoothingAreaBlock.enumSmoothingBlockType;
import com.pg85.otg.exception.InvalidConfigException;
import com.pg85.otg.logging.LogMarker;
import com.pg85.otg.util.ChunkCoordinate;

public class SmoothingAreaColumn
{
	private int x;
	private int z;
	private final ArrayList<SmoothingAreaBlock> blocks = new ArrayList<SmoothingAreaBlock>();
	private SmoothingAreaBlock highestFillingBlock = null;
	private SmoothingAreaBlock lowestCuttingBlock = null;

	public SmoothingAreaColumn(int x, int z)
	{
		this.x = x;
		this.z = z;
	}

	public void addBlock(SmoothingAreaBlock block)
	{
		this.blocks.add(block);
	}

	public void processBlocks(LocalWorldGenRegion worldGenRegion, ChunkCoordinate chunkBeingPopulated, BO4Config bo4Config)
	{
		if(this.highestFillingBlock == null && this.lowestCuttingBlock == null)
		{
	    	// For each column, make sure there is only one cutting line (the lowest cutting block in the column)
	    	// and one filling line (the highest filling block in the column).	
			for(SmoothingAreaBlock block : this.blocks)
			{
				if(block.smoothingBlockType == enumSmoothingBlockType.FILLING && (this.highestFillingBlock == null || this.highestFillingBlock.y < block.y))
				{
					this.highestFillingBlock = block;
				}
				if(block.smoothingBlockType == enumSmoothingBlockType.CUTTING && (this.lowestCuttingBlock == null || this.lowestCuttingBlock.y > block.y))
				{
					this.lowestCuttingBlock = block;
				}
			}
			
	    	// Make sure no cutting line spawns below a filling line (fill overrides cut),
			if(
				this.highestFillingBlock != null && this.lowestCuttingBlock != null &&
				this.lowestCuttingBlock.y > this.highestFillingBlock.y
			)
			{
				// TODO: Won't this still cause wonky results for branches with smoothing areas
				// attached at different heights that overlap?
				this.lowestCuttingBlock.y = this.highestFillingBlock.y;
			}

			// TODO: When using SmoothStartTop:true, if a smoothing line is underneath a bo4 block, we can 
			// cancel spawning the rest of the line since we know we won't need it.
		}
		spawn(worldGenRegion, chunkBeingPopulated, bo4Config);
	}
	
	private void spawn(LocalWorldGenRegion worldGenRegion, ChunkCoordinate chunkBeingPopulated, BO4Config bo4Config)
	{
		BiomeConfig biomeConfig = worldGenRegion.getBiomeConfigForPopulation(this.x, this.z, chunkBeingPopulated);

		LocalMaterialData replaceAboveMaterial = null;
		try {
			replaceAboveMaterial = OTG.getEngine().readMaterial(bo4Config.replaceAbove);
		} catch (InvalidConfigException e) {
			if(OTG.getPluginConfig().spawnLog)
			{
				OTG.log(LogMarker.WARN, "ReplaceAbove: " + bo4Config.replaceAbove + " could not be parsed as a material for BO4 " + bo4Config.getName());
			}
		}

		LocalMaterialData smoothingSurfaceBlock = null;
		LocalMaterialData smoothingGroundBlock = null;
		try {
			smoothingSurfaceBlock = OTG.getEngine().readMaterial(bo4Config.smoothingSurfaceBlock);
		} catch (InvalidConfigException e) {
			if(OTG.getPluginConfig().spawnLog)
			{
				OTG.log(LogMarker.WARN, "SmoothingSurfaceBlock: " + bo4Config.smoothingSurfaceBlock + " could not be parsed as a material for BO4 " + bo4Config.getName());
			}
		}
		try {
			smoothingGroundBlock = OTG.getEngine().readMaterial(bo4Config.smoothingGroundBlock);
		} catch (InvalidConfigException e) {
			if(OTG.getPluginConfig().spawnLog)
			{
				OTG.log(LogMarker.WARN, "SmoothingGroundBlock: " + bo4Config.smoothingGroundBlock + " could not be parsed as a material for BO4 " + bo4Config.getName());
			}
		}
		boolean needsReplaceBlocks;
		LocalMaterialData surfaceBlock;
		LocalMaterialData groundBlock;
		LocalMaterialData blockAbove;
		
		int highestBlockInWorld = -1;
		// If there is a filling block in this column, fill below and replace above.
		// If there is a cutting block and no filling block in this column, replace above.
		if(this.lowestCuttingBlock != null && this.highestFillingBlock == null)
		{
			// ReplaceAbove 
			// Should be AIR, WATER or none
			if(replaceAboveMaterial != null)
			{
				if(highestBlockInWorld == -1)
				{
					highestBlockInWorld = worldGenRegion.getHighestBlockYAt(this.x, this.z, true, false, true, true, true, null);
				}
				
				for(int y = highestBlockInWorld; y > this.lowestCuttingBlock.y; y--)
				{
					if(y > 0)
					{
						worldGenRegion.setBlock(this.lowestCuttingBlock.x, y, this.lowestCuttingBlock.z, replaceAboveMaterial, null, chunkBeingPopulated, false);
					}
				}
				
				// Set the new top block to surface block
				if(highestBlockInWorld > this.lowestCuttingBlock.y && this.lowestCuttingBlock.y > 0)
				{
					// Place the surface block
					surfaceBlock = null;
					needsReplaceBlocks = bo4Config.doReplaceBlocks;
					if(smoothingSurfaceBlock != null && !bo4Config.replaceWithBiomeBlocks)
					{
						surfaceBlock = smoothingSurfaceBlock;
					} else {
						blockAbove = worldGenRegion.getMaterial(this.lowestCuttingBlock.x, this.lowestCuttingBlock.y + 1, this.lowestCuttingBlock.z, chunkBeingPopulated);
						if(blockAbove != null && (blockAbove.isSolid() || blockAbove.isLiquid()))
						{
							surfaceBlock = biomeConfig.surfaceAndGroundControl.getGroundBlockAtHeight(worldGenRegion, biomeConfig, this.lowestCuttingBlock.x, this.lowestCuttingBlock.y, this.lowestCuttingBlock.z);	    							    							
						} else {
							surfaceBlock = biomeConfig.surfaceAndGroundControl.getSurfaceBlockAtHeight(worldGenRegion, biomeConfig, this.lowestCuttingBlock.x, this.lowestCuttingBlock.y, this.lowestCuttingBlock.z);
						}
						needsReplaceBlocks = false;
                        if(surfaceBlock.isAir())
                        {
                            if(
                        		this.lowestCuttingBlock.y < (biomeConfig.useWorldWaterLevel ? worldGenRegion.getWorldConfig().waterLevelMax : biomeConfig.waterLevelMax) &&
                        		worldGenRegion.getMaterial(this.lowestCuttingBlock.x, this.lowestCuttingBlock.y, this.lowestCuttingBlock.z, chunkBeingPopulated).isAir()
                    		)
                            {
                            	surfaceBlock = LocalMaterials.WATER;
                            } else {
                            	surfaceBlock = null;
                            }
                        }
					}
					if(surfaceBlock != null)
					{						
						worldGenRegion.setBlock(this.lowestCuttingBlock.x, this.lowestCuttingBlock.y, this.lowestCuttingBlock.z, surfaceBlock, null, chunkBeingPopulated, needsReplaceBlocks);
					}
				}
			}
		}
		if(this.highestFillingBlock != null)
		{
			if(highestBlockInWorld == -1)
			{
				highestBlockInWorld = worldGenRegion.getHighestBlockYAt(this.x, this.z, true, false, true, true, true, null);
			}
			
			// ReplaceAbove 
			// Should be AIR, WATER or none
			if(replaceAboveMaterial != null)
			{
				for(int y = highestBlockInWorld; y > this.highestFillingBlock.y; y--)
				{
					if(y > 0)
					{
						worldGenRegion.setBlock(this.highestFillingBlock.x, y, this.highestFillingBlock.z, replaceAboveMaterial, null, chunkBeingPopulated, false);
					}
				}
			}
			
			// Place the surface block
			surfaceBlock = null;
			needsReplaceBlocks = bo4Config.doReplaceBlocks;
			if(smoothingSurfaceBlock != null && !bo4Config.replaceWithBiomeBlocks)
			{
				surfaceBlock = smoothingSurfaceBlock;
			} else {
				
				blockAbove = worldGenRegion.getMaterial(this.highestFillingBlock.x, this.highestFillingBlock.y + 1, this.highestFillingBlock.z, chunkBeingPopulated);
				if(blockAbove != null && (blockAbove.isSolid() || blockAbove.isLiquid()))
				{
					surfaceBlock = biomeConfig.surfaceAndGroundControl.getGroundBlockAtHeight(worldGenRegion, biomeConfig, this.highestFillingBlock.x, this.highestFillingBlock.y, this.highestFillingBlock.z);	    							    							
				} else {
					surfaceBlock = biomeConfig.surfaceAndGroundControl.getSurfaceBlockAtHeight(worldGenRegion, biomeConfig, this.highestFillingBlock.x, this.highestFillingBlock.y, this.highestFillingBlock.z);
				}				
				
				needsReplaceBlocks = false;
                if(surfaceBlock.isAir())
                {
                    if(
                		this.highestFillingBlock.y < (biomeConfig.useWorldWaterLevel ? worldGenRegion.getWorldConfig().waterLevelMax : biomeConfig.waterLevelMax) &&
                		worldGenRegion.getMaterial(this.highestFillingBlock.x, this.highestFillingBlock.y, this.highestFillingBlock.z, chunkBeingPopulated).isAir()
            		)
                    {
                    	surfaceBlock = LocalMaterials.WATER;
                    } else {
                		surfaceBlock = null;
                    }
                }
			}
			if(surfaceBlock != null)
			{
				if(this.highestFillingBlock.y > 0)
				{
					worldGenRegion.setBlock(this.highestFillingBlock.x, this.highestFillingBlock.y, this.highestFillingBlock.z, surfaceBlock, null, chunkBeingPopulated, needsReplaceBlocks);
				}
			}
						
			// ReplaceBelow
			if(smoothingGroundBlock != null && !bo4Config.replaceWithBiomeBlocks)
			{
				for(int y = this.highestFillingBlock.y - 1; y >= highestBlockInWorld; y--)
				{
					if(y > 0)
					{
						groundBlock = smoothingGroundBlock;
						needsReplaceBlocks = bo4Config.doReplaceBlocks;
		                if(groundBlock.isAir())
		                {
		                    if(y < (biomeConfig.useWorldWaterLevel ? worldGenRegion.getWorldConfig().waterLevelMax : biomeConfig.waterLevelMax))
		                    {
		                    	groundBlock = LocalMaterials.WATER;
		                    	needsReplaceBlocks = false;
		                    }
		                }
		                worldGenRegion.setBlock(this.highestFillingBlock.x, y, this.highestFillingBlock.z, groundBlock, null, chunkBeingPopulated, needsReplaceBlocks);
					}
				}
			} else {
				for(int y = this.highestFillingBlock.y - 1; y >= highestBlockInWorld; y--)
				{
					if(y > 0)
					{
						groundBlock = biomeConfig.surfaceAndGroundControl.getGroundBlockAtHeight(worldGenRegion, biomeConfig, this.highestFillingBlock.x, y, this.highestFillingBlock.z);
		                if(groundBlock.isAir())
		                {
		                    if(y < (biomeConfig.useWorldWaterLevel ? worldGenRegion.getWorldConfig().waterLevelMax : biomeConfig.waterLevelMax))
		                    {
		                    	groundBlock = LocalMaterials.WATER;
		                    }
		                }
		                worldGenRegion.setBlock(this.highestFillingBlock.x, y, this.highestFillingBlock.z, groundBlock, null, chunkBeingPopulated, false);
					}
				}
			}
		}
	}
}

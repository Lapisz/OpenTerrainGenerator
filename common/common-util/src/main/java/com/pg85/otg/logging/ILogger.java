package com.pg85.otg.logging;

public interface ILogger
{
	public void init(LogMarker level, boolean logCustomObjects, boolean logStructurePlotting, boolean logConfigs, boolean logBiomeRegistry, boolean logDecoration, boolean logMobs);

	public boolean getLogCategoryEnabled(LogCategory category);	
	public void log(LogMarker level, LogCategory category, String message);
	public void printStackTrace(LogMarker marker, LogCategory category, Exception e);	
}

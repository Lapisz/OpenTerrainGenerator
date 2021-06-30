package com.pg85.otg.forge.util;

import com.pg85.otg.constants.Constants;
import com.pg85.otg.logging.LogCategory;
import com.pg85.otg.logging.LogMarker;
import com.pg85.otg.logging.Logger;

import org.apache.logging.log4j.LogManager;

public final class ForgeLogger extends Logger
{
	private final org.apache.logging.log4j.Logger logger = LogManager.getLogger(Constants.MOD_ID_SHORT);

	@Override
	public void log(LogMarker level, LogCategory category, String message)
	{
		if (this.minimumLevel.compareTo(level) < 0)
		{
			// Only log messages that we want to see...
			return;
		}

		// Forge automatically adds the OpenTerrainGenerator prefix, 
		// so we don't need to do that ourselves.

		switch (level)
		{
			case FATAL:
				this.logger.fatal(message);
				break;
			case ERROR:
				this.logger.error(message);
				break;
			case WARN:
				this.logger.warn(message);
				break;
			case INFO:
				this.logger.info(message);
				break;
			case DEBUG:
				// DEBUG only shows up in debug logs
				this.logger.info("DEBUG: " + message);
				break;
			case TRACE:
				// TRACE only shows up in debug logs, also doesn't show up in Eclipse console.
				this.logger.info("TRACE: " + message);
				break;
		}
	}
}

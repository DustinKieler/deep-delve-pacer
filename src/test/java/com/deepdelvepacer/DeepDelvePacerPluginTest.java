package com.deepdelvepacer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class DeepDelvePacerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(DeepDelvePacerPlugin.class);
		RuneLite.main(args);
	}
}

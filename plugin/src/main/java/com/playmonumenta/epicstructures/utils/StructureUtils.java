package com.playmonumenta.epicstructures.utils;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService;
import com.boydti.fawe.util.EditSessionBuilder;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.bukkit.BukkitWorld;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.function.RegionFunction;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.block.BlockTypes;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Chest;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Furnace;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

public class StructureUtils {
	// Ignores structure void, leaving the original block in place
	public static void paste(final Plugin plugin, final BlockArrayClipboard clipboard, final World world, final BlockVector3 to, final boolean includeEntities) {

		final long initialTime = System.currentTimeMillis(); // <-- START

		final Region sourceRegion = clipboard.getRegion();
		final BlockVector3 size = sourceRegion.getMaximumPoint().subtract(sourceRegion.getMinimumPoint());
		final Vector pos1 = new Vector((double)to.getX(), (double)to.getY(), (double)to.getZ());
		final Vector pos2 = pos1.clone().add(new Vector(size.getX() + 1, size.getY() + 1, size.getZ() + 1));
		final BoundingBox box = new BoundingBox(pos1.getX(), pos1.getY(), pos1.getZ(), pos2.getX(), pos2.getY(), pos2.getZ());

		final Region shiftedRegion = clipboard.getRegion().clone();
		shiftedRegion.shift(to);

		final Set<BlockVector2> chunks = shiftedRegion.getChunks();
		final AtomicInteger numRemaining = new AtomicInteger(chunks.size());

		/* Set of positions (relative to the clipboard / origin) that should not be overwritten when pasting */
		final Set<Long> noLoadPositions = new HashSet<>();

		/* This chunk consumer removes entities and sets spawners/brewstands/furnaces to air */
		final Consumer<Chunk> chunkConsumer = (final Chunk chunk) -> {
			numRemaining.decrementAndGet();
			for (final BlockState state : chunk.getTileEntities(true)) {
				if (state instanceof CreatureSpawner || state instanceof BrewingStand || state instanceof Furnace || state instanceof Chest || state instanceof ShulkerBox) {
					final org.bukkit.Location loc = state.getLocation();
					final BlockVector3 relPos = BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()).subtract(to);
					if (box.contains(loc.toVector()) && !clipboard.getBlock(relPos).getBlockType().equals(BlockTypes.STRUCTURE_VOID)) {
						if (state instanceof CreatureSpawner || state instanceof BrewingStand || state instanceof Furnace) {
							// TODO: Work around a bug in FAWE that corrupts these blocks if they're not removed first
							final Block block = state.getBlock();

							/* Set block to air and then dirt... which works around somehow the tile entity data being left behind */
							if (state instanceof BrewingStand || state instanceof Furnace) {
								Inventory inv;
								if (state instanceof BrewingStand) {
									inv = ((BrewingStand)state).getInventory();
								} else {
									inv = ((Furnace)state).getInventory();
								}
								for (int i = 0; i < inv.getSize(); i++) {
									inv.setItem(i, new ItemStack(Material.AIR));
								}
							}
							block.setType(Material.AIR);
							block.setType(Material.DIRT);
						} else if (state instanceof ShulkerBox || ((state instanceof Chest) && isNonemptyGrave((Chest)state))) {
							/* Never overwrite shulker boxes or player graves with contents */
							final int relx = state.getX() - to.getX();
							final int rely = state.getY() - to.getY();
							final int relz = state.getZ() - to.getZ();
							noLoadPositions.add(compressToLong(relx, rely, relz));
						}
					}
				}
			}

			if (includeEntities) {
				for (final Entity entity : chunk.getEntities()) {
					if (box.contains(entity.getLocation().toVector()) && entityShouldBeRemoved(entity)) {
						final Vector relPos = entity.getLocation().toVector().subtract(pos1);
						if (!clipboard.getBlock(BlockVector3.at(relPos.getBlockX(), relPos.getBlockY(), relPos.getBlockZ())).getBlockType().equals(BlockTypes.STRUCTURE_VOID)) {
							entity.remove();
						}
					}
				}
			}
		};

		/* Load all the chunks in the region and run the chunk consumer */
		for (final BlockVector2 chunkCoords : shiftedRegion.getChunks()) {
			world.getChunkAtAsync(chunkCoords.getX(), chunkCoords.getZ(), chunkConsumer);
		}

		new BukkitRunnable() {
			int numTicksWaited = 0;
			@Override
			public void run() {
				numTicksWaited++;
				if (numTicksWaited >= 30 * 20) {
					plugin.getLogger().severe("Timed out waiting for chunks to load to paste structure!");
					this.cancel();
					return;
				}
				if (numRemaining.get() == 0) {
					this.cancel();

					/* Actually load the structure synchronously now that all the chunks have been processed for entities / blocks that shouldn't be replaced */
					Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
						plugin.getLogger().info("Initial processing took " + Long.toString(System.currentTimeMillis() - initialTime) + " milliseconds (mostly async)"); // STOP -->

						final long pasteTime = System.currentTimeMillis(); // <-- START
						try (EditSession extent = new EditSessionBuilder(new BukkitWorld(world))
							.autoQueue(true)
							.fastmode(true)
							.combineStages(true)
							.changeSetNull()
							.checkMemory(false)
							.allowedRegionsEverywhere()
							.limitUnlimited()
							.build()) {

							/*
							 * Filter function to skip some blocks from overwriting what exists in the world
							 * If this function returns true, this location will be overwritten
							 */
							final RegionFunction filterFunction = position -> {
								final BlockType newBlockType = clipboard.getBlock(position).getBlockType();

								if (newBlockType == null || !newBlockType.equals(BlockTypes.STRUCTURE_VOID)) {
									// This position is not in structure void in the clipboard
									if (!noLoadPositions.contains(compressToLong(position.getBlockX(), position.getBlockY(), position.getBlockZ()))) {
										// This position is not in the list of blocks that should not be overwritten
										return true;
									}
								}

								// Don't overwrite by default
								return false;
							};


							final ForwardExtentCopy copy = new ForwardExtentCopy(clipboard, clipboard.getRegion(), clipboard.getOrigin(), extent, to);
							copy.setCopyingBiomes(false);
							copy.setFilterFunction(filterFunction);
							copy.setCopyingEntities(includeEntities);
							Operations.completeBlindly(copy);
						}
						plugin.getLogger().info("Loading structure took " + Long.toString(System.currentTimeMillis() - pasteTime) + " milliseconds (async)"); // STOP -->

						/* Schedule light cleaning on the main thread so it can safely check plugin enabled status */
						Bukkit.getScheduler().runTask(plugin, () -> {
							if (!Bukkit.getPluginManager().isPluginEnabled("LightCleaner")) {
								return;
							}

							final long lightTime = System.currentTimeMillis(); // <-- START

							/* Relight an area 16 blocks bigger than the respawned area */
							final Set<BlockVector2> lightingChunks = new CuboidRegion(to.subtract(16, 16, 16), to.add(size).add(16, 16, 16)).getChunks();
							final LongHashSet lightCleanerChunks = new LongHashSet(lightingChunks.size());
							for (final BlockVector2 chunk : lightingChunks) {
								lightCleanerChunks.add(chunk.getX(), chunk.getZ());
							}
							LightingService.schedule(world, lightCleanerChunks);

							plugin.getLogger().info("scheduleLighting took " + Long.toString(System.currentTimeMillis() - lightTime) + " milliseconds (async)"); // STOP -->
						});
					});
				}
			}
		}.runTaskTimer(plugin, 0, 1);
	}

	private static final EnumSet<EntityType> keptEntities = EnumSet.of(
		EntityType.PLAYER,
		EntityType.DROPPED_ITEM,
		EntityType.EXPERIENCE_ORB,
		EntityType.IRON_GOLEM,
		EntityType.VILLAGER,
		EntityType.TRIDENT,
		EntityType.HORSE,
		EntityType.COW,
		EntityType.PIG,
		EntityType.SHEEP,
		EntityType.CHICKEN
	);

	private static boolean entityShouldBeRemoved(final Entity entity) {
		/* Keep some entity types always */
		if (keptEntities.contains(entity.getType())) {
			return false;
		}

		/* Keep armor stands that have a name, are markers, or have tags */
		if (entity instanceof ArmorStand) {
			final ArmorStand stand = (ArmorStand)entity;
			if ((stand.getCustomName() != null && !stand.getCustomName().isEmpty())
			    || stand.isMarker()
			    || (stand.getScoreboardTags() != null && !stand.getScoreboardTags().isEmpty())) {
				return false;
			}
		}

		/* Keep tameable critters that have an owner */
		if (entity instanceof Tameable) {
			final Tameable critter = (Tameable)entity;
			if (critter.getOwner() != null) {
				return false;
			}
		}

		/* Remove otherwise */
		return true;
	}

	public static void scheduleLighting(final World world, final BlockVector3 to, final BlockVector3 size) {
	}

	private static Long compressToLong(final int x, final int y, final int z) {
		return Long.valueOf(
		           (((long)(x & ((1 << 21) - 1))) << 42) |
		           (((long)(y & ((1 << 21) - 1))) << 21) |
		           ((long)(z & ((1 << 21) - 1)))
		       );
	}

	private static boolean isNonemptyGrave(final Chest chest) {
		if (chest.getCustomName() != null && chest.getCustomName().endsWith("'s Grave")) {
			// Check if the grave has items inside. If it is empty, it can be overwritten.
			for (final ItemStack item : chest.getInventory()) {
				if (item != null) {
					// This is a grave with items left - don't overwrite it
					return true;
				}
			}
		}
		return false;
	}

}

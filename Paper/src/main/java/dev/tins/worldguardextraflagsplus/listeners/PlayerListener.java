package dev.tins.worldguardextraflagsplus.listeners;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.session.SessionManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;

import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.StateFlag.State;
import com.sk89q.worldguard.session.Session;

import lombok.RequiredArgsConstructor;
import dev.tins.worldguardextraflagsplus.Config;
import dev.tins.worldguardextraflagsplus.Messages;
import dev.tins.worldguardextraflagsplus.WorldGuardExtraFlagsPlusPlugin;
import dev.tins.worldguardextraflagsplus.flags.Flags;
import dev.tins.worldguardextraflagsplus.integration.DeluxeCombatIntegration;
import dev.tins.worldguardextraflagsplus.integration.KeepInventoryCombatLogSupport;
import dev.tins.worldguardextraflagsplus.wg.WorldGuardUtils;
import dev.tins.worldguardextraflagsplus.wg.handlers.CollisionFlagHandler;
import dev.tins.worldguardextraflagsplus.wg.handlers.FlyFlagHandler;
import dev.tins.worldguardextraflagsplus.wg.handlers.GiveEffectsFlagHandler;
import dev.tins.worldguardextraflagsplus.wg.handlers.GodmodeFlagHandler;
import dev.tins.worldguardextraflagsplus.wg.handlers.HidePlayersFlagHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
public class PlayerListener implements Listener
{
	private final WorldGuardExtraFlagsPlusPlugin plugin;

	private final WorldGuardPlugin worldGuardPlugin;
	private final RegionContainer regionContainer;
	private final SessionManager sessionManager;
	
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerTeleportEvent(PlayerTeleportEvent event)
	{
		Player player = event.getPlayer();
		
		player.removeMetadata(WorldGuardUtils.PREVENT_TELEPORT_LOOP_META, this.plugin);

		if (!Config.isFlagEnabled("fly"))
		{
			return;
		}

		LocalPlayer localPlayer = this.worldGuardPlugin.wrapPlayer(player);
		if (WorldGuardUtils.hasBypass(this.sessionManager, localPlayer, player))
		{
			return;
		}

		if (this.sessionManager.get(localPlayer).getHandler(FlyFlagHandler.class) == null)
		{
			return;
		}

		this.scheduleFlyRefresh(player);
	}

	/**
	 * Re-applies the fly flag from the regions at the player's location once the teleport or
	 * world change has settled. Queried when the task runs, never from a value cached before the move,
	 * so a player who left a fly region is not given flight back.
	 */
	private void scheduleFlyRefresh(Player player)
	{
		WorldGuardUtils.getScheduler().runAtEntity(player, (wrappedTask) -> {
			if (!player.isOnline())
			{
				return;
			}

			LocalPlayer lp = this.worldGuardPlugin.wrapPlayer(player);
			FlyFlagHandler flyHandler = this.sessionManager.get(lp).getHandler(FlyFlagHandler.class);
			if (flyHandler == null)
			{
				return;
			}

			if (this.sessionManager.hasBypass(lp, lp.getWorld()))
			{
				return;
			}

			ApplicableRegionSet regions = this.regionContainer.createQuery().getApplicableRegions(lp.getLocation());
			flyHandler.refreshFlyFromApplicableSet(lp, regions);
		});
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerDeathEvent(PlayerDeathEvent event)
	{
		Player player = event.getEntity();

		LocalPlayer localPlayer = this.worldGuardPlugin.wrapPlayer(player);
		ApplicableRegionSet regions = this.regionContainer.createQuery().getApplicableRegions(WorldGuardUtils.adaptLocation(player.getLocation()));
		
		Boolean keepInventory = regions.queryValue(localPlayer, Flags.KEEP_INVENTORY);
		if (keepInventory != null)
		{
			event.setKeepInventory(keepInventory);
			
			if (keepInventory)
			{
				event.getDrops().clear();
			}
		}
		
		Boolean keepExp = regions.queryValue(localPlayer, Flags.KEEP_EXP);
		if (keepExp != null)
		{
			event.setKeepLevel(keepExp);

			if (keepExp)
			{
				event.setDroppedExp(0);
			}
		}

		KeepInventoryCombatLogSupport.clear(player);
	}

	@EventHandler(ignoreCancelled = true)
	public void onAsyncPlayerChatEvent(io.papermc.paper.event.player.AsyncChatEvent event)
	{
		Player player = event.getPlayer();
		LocalPlayer localPlayer = this.worldGuardPlugin.wrapPlayer(player);
		ApplicableRegionSet regions = this.regionContainer.createQuery().getApplicableRegions(WorldGuardUtils.adaptLocation(player.getLocation()));
		String prefix = regions.queryValue(localPlayer, Flags.CHAT_PREFIX);
		String suffix = regions.queryValue(localPlayer, Flags.CHAT_SUFFIX);
		if (prefix == null && suffix == null) return;
		io.papermc.paper.chat.ChatRenderer previous = event.renderer();
		event.renderer((source, displayName, message, audience) -> {
			Player viewer = audience instanceof Player recipient ? recipient : source;
			net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer legacy = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
			return legacy.deserialize(Messages.configured(viewer, source, prefix))
					.append(previous.render(source, displayName, message, audience))
					.append(legacy.deserialize(Messages.configured(viewer, source, suffix)));
		});
	}

	

	@EventHandler(ignoreCancelled = true)
	public void onPlayerRespawnEvent(PlayerRespawnEvent event)
	{
		Player player = event.getPlayer();
		LocalPlayer localPlayer = this.worldGuardPlugin.wrapPlayer(player);
		
		Location respawnLocation = this.regionContainer.createQuery().queryValue(WorldGuardUtils.adaptLocation(player.getLocation()), localPlayer, Flags.RESPAWN_LOCATION);
		if (respawnLocation != null)
		{
			event.setRespawnLocation(BukkitAdapter.adapt(respawnLocation));
		}
	}
	
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerItemConsumeEvent(PlayerItemConsumeEvent event)
	{
		if (!Config.isFlagEnabled("give-effects"))
		{
			return;
		}

		Player player = event.getPlayer();

		GiveEffectsFlagHandler giveEffects = this.sessionManager.get(this.worldGuardPlugin.wrapPlayer(player))
				.getHandler(GiveEffectsFlagHandler.class);
		if (giveEffects == null)
		{
			return;
		}

		ItemMeta itemMeta = event.getItem().getItemMeta();
		if (itemMeta instanceof PotionMeta potionMeta)
		{
			List<PotionEffect> effects = new ArrayList<>();
			if (potionMeta.getBasePotionType() != null)
			{
				effects.addAll(potionMeta.getBasePotionType().getPotionEffects());
			}

			effects.addAll(potionMeta.getCustomEffects());

			giveEffects.drinkPotion(player, effects);
		}
		else
		{
			Material material = event.getItem().getType();
			if (material == Material.MILK_BUCKET)
			{
				giveEffects.drinkMilk(player);
			}
		}
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlayerGameModeChangeEvent(PlayerGameModeChangeEvent event)
	{
		Player player = event.getPlayer();
		
		Session wgSession = this.sessionManager.getIfPresent(this.worldGuardPlugin.wrapPlayer(player));
		if (wgSession != null)
		{
			if (!Config.isFlagEnabled("fly"))
			{
				return;
			}
			FlyFlagHandler flyHandler = wgSession.getHandler(FlyFlagHandler.class);
			if (flyHandler == null)
			{
				return;
			}
			Boolean value = flyHandler.getCurrentValue();
			if (value != null)
			{
				WorldGuardUtils.getScheduler().runAtEntity(player, (wrappedTask) -> checkFlyStatus(player, player.getAllowFlight()));
			}
			return;
		}
		WorldGuardUtils.getScheduler().runAtEntity(player, (wrappedTask) -> checkFlyStatus(player, null));
	}
	
	private void checkFlyStatus(Player player, Boolean originalValueOverwrite)
	{
		if (!Config.isFlagEnabled("fly"))
		{
			return;
		}

		FlyFlagHandler flyFlagHandler = this.sessionManager.get(this.worldGuardPlugin.wrapPlayer(player)).getHandler(FlyFlagHandler.class);
		if (flyFlagHandler == null)
		{
			return;
		}

		Boolean currentValue = flyFlagHandler.getCurrentValue();
		if (currentValue != null)
		{
			player.setAllowFlight(currentValue);
		}

		if (originalValueOverwrite != null)
		{
			flyFlagHandler.setOriginalFly(originalValueOverwrite);
		}
	}

	@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
	public void onPlayerItemDamageEvent(PlayerItemDamageEvent event)
	{
		Player player = event.getPlayer();
		LocalPlayer localPlayer = this.worldGuardPlugin.wrapPlayer(player);

		if (this.regionContainer.createQuery().queryState(WorldGuardUtils.adaptLocation(player.getLocation()), localPlayer, Flags.ITEM_DURABILITY) == State.DENY)
		{
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerJoinEvent(PlayerJoinEvent event)
	{
		Player player = event.getPlayer();

		if (Config.isFlagEnabled("fly"))
		{
			FlyFlagHandler flyHandler = this.sessionManager.get(this.worldGuardPlugin.wrapPlayer(player)).getHandler(FlyFlagHandler.class);
			if (flyHandler != null)
			{
				Boolean flyValue = flyHandler.getCurrentValue();
				if (flyValue != null)
				{
					WorldGuardUtils.getScheduler().runAtEntity(player, (wrappedTask) -> player.setAllowFlight(flyValue));
				}
			}
		}
		
		// Check collision flag for players already in regions on join
		// Query the region directly to ensure collision is applied even if handler hasn't triggered yet
		LocalPlayer localPlayer = this.worldGuardPlugin.wrapPlayer(player);
		ApplicableRegionSet regions = this.regionContainer.createQuery().getApplicableRegions(WorldGuardUtils.adaptLocation(player.getLocation()));
		Boolean collisionValue = regions.queryValue(localPlayer, Flags.DISABLE_COLLISION);
		if (collisionValue != null && collisionValue)
		{
			this.applyCollisionOnJoinOrWorldChange(player, localPlayer, collisionValue);
		}

		if (Config.isFlagEnabled("keep-inventory") && Config.isCombatLogKeepInventoryEnabled())
		{
			WorldGuardUtils.getScheduler().runAtEntity(player, (wrappedTask) ->
					KeepInventoryCombatLogSupport.restoreIfPending(player));
		}

		if (Config.isFlagEnabled("hide-players"))
		{
			HidePlayersFlagHandler.applyVisibilityForJoiningPlayer(player);
		}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerChangedWorldEvent(PlayerChangedWorldEvent event)
	{
		Player player = event.getPlayer();

		//Some plugins toggle flight off on world change based on permissions,
		//so we need to make sure to force the flight status.
		//The cached value may belong to the region the player just left (the exit update is still
		//queued), so re-query the destination instead of re-applying it (#8).
		if (Config.isFlagEnabled("fly"))
		{
			FlyFlagHandler flyHandler = this.sessionManager.get(this.worldGuardPlugin.wrapPlayer(player)).getHandler(FlyFlagHandler.class);
			if (flyHandler != null && flyHandler.getCurrentValue() != null)
			{
				this.scheduleFlyRefresh(player);
			}
		}
		
		// Check collision flag on world change
		LocalPlayer localPlayer = this.worldGuardPlugin.wrapPlayer(player);
		ApplicableRegionSet regions = this.regionContainer.createQuery().getApplicableRegions(WorldGuardUtils.adaptLocation(player.getLocation()));
		Boolean collisionValue = regions.queryValue(localPlayer, Flags.DISABLE_COLLISION);
		if (collisionValue != null && collisionValue)
		{
			this.applyCollisionOnJoinOrWorldChange(player, localPlayer, collisionValue);
		}
	}

	@EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
	public void onPlayerQuitCombatLogKeepInventory(PlayerQuitEvent event)
	{
		if (!Config.isFlagEnabled("keep-inventory") || !Config.isCombatLogKeepInventoryEnabled())
		{
			return;
		}

		Player player = event.getPlayer();
		LocalPlayer localPlayer = this.worldGuardPlugin.wrapPlayer(player);

		if (WorldGuardUtils.hasBypass(this.sessionManager, localPlayer, player))
		{
			return;
		}

		ApplicableRegionSet regions = this.regionContainer.createQuery().getApplicableRegions(WorldGuardUtils.adaptLocation(player.getLocation()));
		Boolean keepInventory = regions.queryValue(localPlayer, Flags.KEEP_INVENTORY);
		if (!Boolean.TRUE.equals(keepInventory))
		{
			return;
		}

		if (!DeluxeCombatIntegration.isInCombat(player))
		{
			return;
		}

		KeepInventoryCombatLogSupport.rememberCombatLogQuit(player);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerQuit(PlayerQuitEvent event)
	{
		java.util.UUID playerId = event.getPlayer().getUniqueId();
		FlyFlagHandler.clearPlayerCaches(playerId);
		GodmodeFlagHandler.clearPlayerCaches(playerId);
		HidePlayersFlagHandler.clearPlayerCaches(playerId);
		Messages.clearCooldown(event.getPlayer());
	}

	private void applyCollisionOnJoinOrWorldChange(Player player, LocalPlayer localPlayer, Boolean collisionValue)
	{
		WorldGuardUtils.getScheduler().runAtEntity(player, (wrappedTask) -> {
			if (!player.isOnline())
			{
				return;
			}

			CollisionFlagHandler handler = this.sessionManager.get(localPlayer).getHandler(CollisionFlagHandler.class);
			if (handler != null)
			{
				handler.applyCollisionSettingPublic(player, collisionValue);
			}
		});
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
	public void onPlayerDropItem(PlayerDropItemEvent event)
	{
		Player player = event.getPlayer();
		LocalPlayer localPlayer = this.worldGuardPlugin.wrapPlayer(player);
		
		if (WorldGuardUtils.hasBypass(this.sessionManager, localPlayer, player))
		{
			return;
		}
		
		Material itemType = event.getItemDrop().getItemStack().getType();
		Location location = BukkitAdapter.adapt(player.getLocation());
		ApplicableRegionSet regions = this.regionContainer.createQuery().getApplicableRegions(location);
		
		// Check our deny-item-drops flag (denies specific items even if WorldGuard allows drops)
		Set<Material> denySet = regions.queryValue(localPlayer, Flags.DENY_ITEM_DROPS);
		if (denySet != null && !denySet.isEmpty() && denySet.contains(itemType))
		{
			// Item is in deny list, deny it
			event.setCancelled(true);
		}
	}

	@EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
	public void onEntityPickupItem(EntityPickupItemEvent event)
	{
		if (!(event.getEntity() instanceof Player player))
		{
			return;
		}
		
		LocalPlayer localPlayer = this.worldGuardPlugin.wrapPlayer(player);
		
		if (WorldGuardUtils.hasBypass(this.sessionManager, localPlayer, player))
		{
			return;
		}
		
		Material itemType = event.getItem().getItemStack().getType();
		// Check item location for deny-item-pickup flag
		Location itemLocation = BukkitAdapter.adapt(event.getItem().getLocation());
		ApplicableRegionSet regions = this.regionContainer.createQuery().getApplicableRegions(itemLocation);
		
		// Check our deny-item-pickup flag (denies specific items even if WorldGuard allows pickups)
		Set<Material> denySet = regions.queryValue(localPlayer, Flags.DENY_ITEM_PICKUP);
		if (denySet != null && !denySet.isEmpty() && denySet.contains(itemType))
		{
			// Item is in deny list, deny it
			event.setCancelled(true);
		}
	}
	
}




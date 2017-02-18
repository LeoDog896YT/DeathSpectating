package to.us.tf.DeathSpectating;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import to.us.tf.DeathSpectating.events.DeathSpectatingEvent;
import to.us.tf.DeathSpectating.listeners.DamageListener;
import to.us.tf.DeathSpectating.listeners.MiscListeners;
import to.us.tf.DeathSpectating.tasks.SpectateTask;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Created by Robo on 2/15/2017.
 */
public class DeathSpectating extends JavaPlugin implements Listener
{
    private ConfigManager configManager;

    public void onEnable()
    {
        configManager = new ConfigManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new DamageListener(this), this);
        getServer().getPluginManager().registerEvents(new MiscListeners(this), this);
    }

    public ConfigManager getConfigManager()
    {
        return configManager;
    }

    public long getRespawnTicks()
    {
        return configManager.getRespawnTicks();
    }

    public boolean isSpectatingTeleportEnabled(Player player)
    {
        return false;
    }

    public boolean isSpectating(Player player)
    {
        return player.hasMetadata("DEAD");
    }

    /**
     * Tags/untags a player as spectating ("DEAD")
     * Also automatically sets/resets spectating attributes
     * @param player
     * @param spectate
     * */
    public void setSpectating(Player player, boolean spectate)
    {
        if (spectate)
        {
            player.setMetadata("DEAD", new FixedMetadataValue(this, true));
            player.setGameMode(GameMode.SPECTATOR);
            player.setFlySpeed(0.0f);
        }
        else
        {
            player.removeMetadata("DEAD", this);
            player.setLastDamageCause(null);
            player.setGameMode(getServer().getDefaultGameMode());
            player.setFlySpeed(0.2f);
        }
    }

    public boolean respawnPlayer(Player player)
    {
        if (!isSpectating(player))
            return false;
        setSpectating(player, false);

        //TODO: Non-Vanilla behavior - can't determine whether to tell player their "bed is missing or obstructed" (Issue #12)
        //Reason: No non-CB/NMS way to determine if player has set a bed spawn before or not.
        //There is respawnLocation or similar method inside of CraftPlayer, including whether "bed spawn" is forced or not

        Location spawnLocation = player.getBedSpawnLocation();
        boolean bedSpawn = true;

        //http://i.minidigger.me/2017/02/idea64_16_17-53-36.png
        //i.e., bedLocation will be null if obstructed
        //CB sets respawnLocation as per below if this is the case
        if (spawnLocation == null)
        {
            spawnLocation = getServer().getWorlds().get(0).getSpawnLocation();
            bedSpawn = false;
        }

        /*Fire PlayerRespawnEvent*/
        PlayerRespawnEvent respawnEvent = new PlayerRespawnEvent(player, spawnLocation, bedSpawn);
        getServer().getPluginManager().callEvent(respawnEvent);

        /*undo spectating attributes*/
        //Player#isDead() == true when PlayerRespawnEvent is fired.
        //This is done before teleporting in case a teleport/worldchange event handler wants to set other gamemodes instead (like Multiverse)
        //Oh, and also because the MiscListeners would cancel this teleport otherwise
        setSpectating(player, false);

        //Teleport player to event#getRespawnLocation
        //CB doesn't nullcheck it seems, so neither will I
        player.teleport(respawnEvent.getRespawnLocation(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        return true;
    }

    /**
     * "Kill" player and put them in death spectating mode
     * @param player
     * @return if player was put in death spectating mode
     */
    public boolean startDeathSpectating(Player player)
    {
        if (isSpectating(player))
            return false;

        try
        {
            /*Set spectating attributes*/
            //Player#isDead() == true when PlayerDeathEvent is fired.
            setSpectating(player, true);

            /*Start Death simulation*/

            boolean keepInventory = Boolean.valueOf(player.getWorld().getGameRuleValue("keepInventory"));
            boolean showDeathMessages = Boolean.valueOf(player.getWorld().getGameRuleValue("showDeathMessages"));

            //+phoenix616: RoboMWM: it will drop level * 7 exp and 100 as a maximum
            //see https://minecraft.gamepedia.com/Health#Death
            int expToDrop = SetExpFix.getTotalExperience(player);
            if (expToDrop > 100)
                expToDrop = 100;

            List<ItemStack> itemsToDrop = new ArrayList<>(player.getInventory().getSize());
            if (!keepInventory)
            {
                //Compile a list of null-free/air-free items to drop
                for (ItemStack itemStack : player.getInventory().getContents())
                {
                    if (itemStack != null && itemStack.getType() != Material.AIR && itemStack.containsEnchantment(Enchantment.VANISHING_CURSE))
                        itemsToDrop.add(itemStack);
                }
            }

            //TODO: Non-vanilla behavior, see issue #4
            String deathMessage = "";

            /*Fire PlayerDeathEvent*/
            PlayerDeathEvent deathEvent = new PlayerDeathEvent(player, itemsToDrop, expToDrop, deathMessage);
            deathEvent.setKeepInventory(keepInventory); //CB's constructor does indeed set whether the inventory is kept or not, using the gamerule's value
            getServer().getPluginManager().callEvent(deathEvent);

            //TODO: Non-vanilla behavior, see issue #5
            //Print death message
            if (deathEvent.getDeathMessage() != null && !deathEvent.getDeathMessage().isEmpty() && showDeathMessages)
                getServer().broadcastMessage(deathEvent.getDeathMessage());

            //Clear and drop items
            if (!deathEvent.getKeepInventory())
            {
                player.getInventory().clear();
                for (ItemStack itemStack : deathEvent.getDrops())
                {
                    if (itemStack != null && itemStack.getType() != Material.AIR)
                        player.getWorld().dropItemNaturally(player.getLocation(), itemStack);
                }
            }

            //Clear and set experience, if getKeepLevel() == false
            if (!deathEvent.getKeepLevel())
            {
                SetExpFix.setTotalExperience(player, 0);
                player.setTotalExperience(deathEvent.getNewTotalExp());
                player.setLevel(deathEvent.getNewLevel());
                player.setExp(deathEvent.getNewExp());
            }

            //Drop experience
            if (deathEvent.getDroppedExp() > 0)
                player.getWorld().spawn(player.getLocation(), ExperienceOrb.class).setExperience(deathEvent.getDroppedExp());

            //Close any inventory the player may be viewing
            player.closeInventory();

            //Increment/reset death statistics
            player.incrementStatistic(Statistic.DEATHS);
            player.setStatistic(Statistic.TIME_SINCE_DEATH, 0);

            //TODO: Non-vanilla behavior: Player death animation (red and falling over) (Issue #13)
            //TODO: Non-vanilla behavior: Smoke effect (Issue #14)

            //Clear potion effects
            for (PotionEffect potionEffect : player.getActivePotionEffects())
                player.removePotionEffect(potionEffect.getType());

            /* End Death simulation*/

            //Determine what entity killed this player (Entity#getKiller can only return a Player)
            Entity killer = player.getKiller();
            if (player.getKiller() == null && player.getLastDamageCause() instanceof EntityDamageByEntityEvent)
            {
                killer = ((EntityDamageByEntityEvent) player.getLastDamageCause()).getDamager();
                if (killer != null && killer instanceof Projectile)
                {
                    Projectile arrow = (Projectile) killer;
                    if (arrow.getShooter() instanceof LivingEntity)
                        killer = (Entity) arrow.getShooter();
                    arrow.remove(); //Delete projectile
                }
            }
            if (killer == player) //Though we don't care if they did it themselves
                killer = null;

            //Increment killer's PLAYER_KILLS
            if (killer != null && killer.getType() == EntityType.PLAYER)
            {
                Player playerKiller = (Player)killer;
                playerKiller.incrementStatistic(Statistic.PLAYER_KILLS);
            }

            //Increment ENTITY_KILLED_BY if killer is not null and is a creature
            if (killer != null && killer instanceof Creature)
                player.incrementStatistic(Statistic.ENTITY_KILLED_BY, killer.getType());

            /*Start death spectating!*/
            SpectateTask task = new SpectateTask(player, getRespawnTicks(), killer, this);
            getServer().getPluginManager().callEvent(new DeathSpectatingEvent(task));
            task.runTaskTimer(this, 1L, 1L);

            //Send player a message that they were killed and are now spectating, if configured to do so
            if (!configManager.getYouDiedMessage().isEmpty())
                player.sendMessage(configManager.getYouDiedMessage());

            return true;
        }
        catch (Exception e)
        {
            getLogger().log(Level.SEVERE, "An error occurred while trying to start death spectating for " + player.getName());
            getLogger().log(Level.SEVERE, "Report the following stacktrace in full:\n");
            e.printStackTrace();
            setSpectating(player, false);
            return false;
        }
    }
}

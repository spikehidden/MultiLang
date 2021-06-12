/*
 * This file is part of MultiLang, licensed under the MIT License.
 *
 * Copyright (c) Lorenzo0111
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package me.lorenzo0111.multilang;

import me.lorenzo0111.multilang.api.objects.Cache;
import me.lorenzo0111.multilang.api.objects.LocalizedPlayer;
import me.lorenzo0111.multilang.cache.PlayersCache;
import me.lorenzo0111.multilang.data.StorageType;
import me.lorenzo0111.multilang.database.DatabaseManager;
import me.lorenzo0111.multilang.exceptions.ReloadException;
import me.lorenzo0111.multilang.handlers.ConfigManager;
import me.lorenzo0111.multilang.listeners.JoinListener;
import me.lorenzo0111.multilang.storage.StorageManager;
import me.lorenzo0111.multilang.tasks.UpdateTask;
import me.lorenzo0111.multilang.utils.PluginLoader;
import me.lorenzo0111.pluginslib.database.objects.Column;
import me.lorenzo0111.pluginslib.database.objects.Table;
import me.lorenzo0111.pluginslib.dependency.DependencyManager;
import me.lorenzo0111.pluginslib.dependency.objects.Dependency;
import me.lorenzo0111.pluginslib.updater.UpdateChecker;
import me.lorenzo0111.rocketplaceholders.api.RocketPlaceholdersAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.MalformedURLException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

public final class MultiLangPlugin extends JavaPlugin {
    private RocketPlaceholdersAPI rocketPlaceholdersAPI;
    private ConfigManager configManager;
    private PluginLoader loader;
    private static MultiLangPlugin instance;
    private DatabaseManager databaseManager;
    private final Cache<UUID,LocalizedPlayer> playerCache = new PlayersCache();
    private File cacheFolder;
    private StorageManager storage;
    private StorageType type;
    private DependencyManager manager;
    public final static String MAVEN = "https://repo1.maven.org/maven2/";
    private UpdateChecker updater;

    @Override
    public void onEnable() {
        instance = this;
        this.saveDefaultConfig();
        this.loadDependencies();
    }

    private void loadPlugin() {
        this.loader = new PluginLoader(this);
        this.storage = new StorageManager(this);

        if (!loader.init()) {
            return;
        }

        this.getServer().getPluginManager().registerEvents(new JoinListener(this),this);

        Bukkit.getScheduler().runTaskTimer(this,
                new UpdateTask(this),
                60 * 20L,120 * 20L);

        loader.commands();
        loader.api();
        loader.gui();
        loader.messages();
        loader.metrics();

        this.updater = new UpdateChecker(this,93235,"https://www.spigotmc.org/resources/93235/",null);

        this.resetConnection();

        this.cacheFolder = new File(this.getDataFolder(), "cache");

        if (!cacheFolder.exists() && !cacheFolder.mkdir()) {
            this.getLogger().warning("Unable to create cache folder, dump file won't saved.");
        }
    }

    @Override
    public void onDisable() {
        this.configManager.unregisterAll();
        Bukkit.getScheduler().cancelTasks(this);
        this.getLogger().info("Closing database connection..");

        try {
            if (this.getDatabaseManager().getConnection() != null)
                this.getDatabaseManager().getConnection().close();
        } catch (SQLException e) {
            this.getLogger().warning("An error has occurred while closing database connection: " + e.getMessage());
        }

        this.getLogger().info("Cleaning cache..");

        this.getPlayerCache().reset();

        if (this.cacheFolder == null) {
            return;
        }

        File[] files = this.cacheFolder.listFiles(File::isFile);

        if (files != null) {
            Arrays.stream(files)
                    .filter((file) -> file.getName().endsWith(".json"))
                    .forEach(File::delete);
        }
    }

    public RocketPlaceholdersAPI getRocketPlaceholdersAPI() {
        return rocketPlaceholdersAPI;
    }

    public void setRocketPlaceholdersAPI(RocketPlaceholdersAPI rocketPlaceholdersAPI) {
        this.rocketPlaceholdersAPI = rocketPlaceholdersAPI;
    }

    public void setConfigManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public static MultiLangPlugin getInstance() {
        return instance;
    }

    public PluginLoader getLoader() {
        return loader;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public Cache<UUID, LocalizedPlayer> getPlayerCache() {
        return playerCache;
    }

    public String getConfig(String path) {
        return this.getConfig().getString(path);
    }

    public File getCacheFolder() {
       return this.cacheFolder;
    }

    public void resetConnection() throws ReloadException {
        this.type = StorageType.valueOf(this.getConfig("storage"));
        Objects.requireNonNull(this.type, "Invalid storage type");

        Objects.requireNonNull(this.getStorageType()
                .install(this))
                .thenRun(() -> {
                    final Connection connection = DatabaseManager.createConnection(this);
                    if (connection == null) throw new ReloadException("Connection cannot be null");

                    Table playersTable = new Table
                            (this,
                                    connection,
                                    "players",
                                    Arrays.asList(
                                            new Column("uuid", "STRING"),
                                            new Column("locale", "STRING")
                                    ));

                    this.databaseManager = new DatabaseManager(this, Collections.singletonList(playersTable), connection);
                });
    }

    public StorageManager getStorage() {
        return storage;
    }

    public StorageType getStorageType() {
        return type;
    }

    public DependencyManager getDependencyManager() {
        return manager;
    }

    private void loadDependencies() {
        long time = System.currentTimeMillis();
        this.getLogger().info("Loading libraries..");
        File libsFolder = new File(this.getDataFolder(), "libs");
        if (libsFolder.mkdirs()) {
            this.getLogger().info("Libs folder created");
        }

        this.manager = new DependencyManager(this, libsFolder);

        try {
            manager.addDependency(new Dependency("me.mattstudios.utils", "matt-framework-gui", "2.0.3.3", MAVEN));
            manager.addDependency(new Dependency("com.github.cryptomorin", "XSeries", "7.9.1.1", MAVEN));
            manager.installAll()
                    .thenRun(() -> {
                        this.getLogger().info(String.format("Loaded all dependencies in %sms.", System.currentTimeMillis() - time));
                        this.loadPlugin();
                    });
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

    public UpdateChecker getUpdater() {
        return updater;
    }
}

package hu.smpcore;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Automatikus mentés: a világok és a plugin adatai zipbe kerülnek a szerver mappa "backups" mappájába.
 * Mentés közben a világ automatikus mentése szünetel (save-off), a végén visszakapcsol (save-on).
 */
public final class BackupManager implements CommandExecutor {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final SMPCore plugin;
    private BukkitTask task;
    private volatile boolean running;

    public BackupManager(SMPCore plugin) {
        this.plugin = plugin;
    }

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("backup.enabled", true)) {
            return;
        }
        long minutes = Math.max(10, plugin.getConfig().getLong("backup.interval-minutes", 120));
        long ticks = minutes * 60L * 20L;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> run(null), ticks, ticks);
    }

    /** Leállításkor is hívjuk: ha épp mentés közben állunk le, a világ mentését visszakapcsoljuk. */
    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (running) {
            Server server = plugin.getServer();
            server.dispatchCommand(server.getConsoleSender(), "save-on");
            running = false;
        }
    }

    public void run(CommandSender requester) {
        if (running) {
            if (requester != null) {
                plugin.send(requester, "backup-running");
            }
            return;
        }
        running = true;
        Server server = plugin.getServer();
        if (requester != null) {
            plugin.send(requester, "backup-start");
        }
        plugin.getLogger().info("Világmentés indul...");

        server.dispatchCommand(server.getConsoleSender(), "save-all flush");
        server.dispatchCommand(server.getConsoleSender(), "save-off");

        Path base = server.getWorldContainer().toPath().toAbsolutePath().normalize();
        List<File> sources = new ArrayList<>();
        for (World world : server.getWorlds()) {
            sources.add(world.getWorldFolder());
        }
        sources.add(plugin.getDataFolder());
        File outDir = new File(base.toFile(), plugin.getConfig().getString("backup.folder", "backups"));
        int keep = Math.max(1, plugin.getConfig().getInt("backup.keep", 10));

        server.getScheduler().runTaskAsynchronously(plugin, () -> {
            File zip = new File(outDir, "backup-" + LocalDateTime.now().format(FORMAT) + ".zip");
            boolean ok = false;
            try {
                outDir.mkdirs();
                zipAll(sources, zip, base);
                prune(outDir, keep);
                ok = true;
            } catch (Exception ex) {
                plugin.getLogger().severe("A mentés nem sikerült: " + ex.getMessage());
            }
            boolean success = ok;
            try {
                server.getScheduler().runTask(plugin, () -> {
                    server.dispatchCommand(server.getConsoleSender(), "save-on");
                    running = false;
                    plugin.getLogger().info(success ? "Világmentés kész: " + zip.getName() : "Világmentés sikertelen.");
                    if (requester != null) {
                        if (success) {
                            plugin.send(requester, "backup-done", Placeholder.unparsed("file", zip.getName()));
                        } else {
                            plugin.send(requester, "backup-failed");
                        }
                    }
                });
            } catch (RuntimeException ex) {
                // a plugin közben leállt, a stop() már visszakapcsolta a mentést
            }
        });
    }

    private void zipAll(List<File> sources, File zipFile, Path base) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(zipFile.toPath())))) {
            for (File source : sources) {
                Path start = source.toPath().toAbsolutePath().normalize();
                if (!Files.exists(start)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(start)) {
                    for (Path path : (Iterable<Path>) walk::iterator) {
                        if (!Files.isRegularFile(path) || path.getFileName().toString().equals("session.lock")) {
                            continue;
                        }
                        String name = base.relativize(path).toString().replace('\\', '/');
                        try {
                            zos.putNextEntry(new ZipEntry(name));
                            Files.copy(path, zos);
                            zos.closeEntry();
                        } catch (IOException ex) {
                            // zárolt vagy közben változó fájl, kihagyjuk
                        }
                    }
                }
            }
        }
    }

    private void prune(File dir, int keep) {
        File[] files = dir.listFiles((d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
        if (files == null || files.length <= keep) {
            return;
        }
        Arrays.sort(files);
        for (int i = 0; i < files.length - keep; i++) {
            if (!files[i].delete()) {
                plugin.getLogger().warning("Nem sikerült törölni a régi mentést: " + files[i].getName());
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("smpcore.admin")) {
            plugin.send(sender, "no-permission");
            return true;
        }
        run(sender);
        return true;
    }
}

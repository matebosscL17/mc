package hu.smpcore;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.Random;

/** A nagy lobby terve: lebegő sziget kertekkel, szökőkúttal, ösvényekkel, kapukkal, láda-csarnokkal és szigetekkel. */
final class LobbyDesign {

    private LobbyDesign() {
    }

    /** A ládák helye a középponthoz képest (x, z) és a láda azonosítója. */
    private static int[][] crateSpots(int radius) {
        int z = -(int) Math.round(radius * 0.55);
        return new int[][]{{-9, z}, {0, z}, {9, z}};
    }

    private static final String[] CRATE_IDS = {"common", "rare", "legendary"};

    static List<LobbyManager.Op> plan(int cx, int cy, int cz, int radius) {
        LobbyManager.Planner p = new LobbyManager.Planner(cx, cy, cz);
        Random rnd = new Random(radius * 31L + 7);
        int limit = radius * radius + radius;
        double plazaR = radius * 0.5;
        double gardenR = radius - 3.5;

        // 1) Tér kitisztítása (hogy a régi dolgok ne zavarjanak)
        for (int dy = 1; dy <= 22; dy++) {
            p.disc(radius + 2, dy, Material.AIR);
        }

        // 2) Sziget teste: lefelé szűkülő kúp, rétegekkel
        int depth = Math.max(8, radius / 2);
        for (int k = 1; k <= depth; k++) {
            int r = Math.max(2, (int) Math.round(radius * (1.0 - (double) k / (depth + 2))));
            Material m = k % 4 == 0 ? Material.POLISHED_BLACKSTONE_BRICKS
                    : k % 3 == 0 ? Material.DEEPSLATE_BRICKS : k == 1 ? Material.GOLD_BLOCK : Material.BLACKSTONE;
            p.disc(r, -k, m);
        }

        // 3) Felszín: terasz (kvarc sakktábla), kert (fű), arany peremgyűrű
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > limit) {
                    continue;
                }
                double d = Math.sqrt(d2);
                Material top;
                if (d > radius - 1.5) {
                    top = Material.GOLD_BLOCK;
                } else if (d > gardenR) {
                    top = Material.POLISHED_BLACKSTONE_BRICKS;
                } else if (d <= plazaR) {
                    top = Math.floorMod(dx + dz, 2) == 0 ? Material.QUARTZ_BLOCK : Material.SMOOTH_QUARTZ;
                } else {
                    top = Material.GRASS_BLOCK;
                }
                p.set(dx, 0, dz, top);
                if (top == Material.GRASS_BLOCK) {
                    int roll = rnd.nextInt(100);
                    if (roll < 9) {
                        Material[] flowers = {Material.POPPY, Material.AZURE_BLUET, Material.CORNFLOWER,
                                Material.PINK_TULIP, Material.ALLIUM, Material.OXEYE_DAISY};
                        p.set(dx, 1, dz, flowers[rnd.nextInt(flowers.length)]);
                    } else if (roll < 22) {
                        p.set(dx, 1, dz, Material.SHORT_GRASS);
                    }
                }
            }
        }

        // 4) Négy ösvény a közepétől a pereméig (5 széles, kvarc szegéllyel)
        for (int t = 0; t <= radius - 2; t++) {
            for (int w = -2; w <= 2; w++) {
                Material m = Math.abs(w) == 2 ? Material.QUARTZ_BLOCK : Material.POLISHED_BLACKSTONE;
                if (Math.abs(w) <= 1 && t % 6 == 3 && w == 0) {
                    m = Material.SEA_LANTERN;
                }
                p.set(t, 0, w, m);
                p.set(-t, 0, w, m);
                p.set(w, 0, t, m);
                p.set(w, 0, -t, m);
                // virágok eltakarítása az ösvényről
                for (int dy = 1; dy <= 2; dy++) {
                    p.set(t, dy, w, Material.AIR);
                    p.set(-t, dy, w, Material.AIR);
                    p.set(w, dy, t, Material.AIR);
                    p.set(w, dy, -t, Material.AIR);
                }
            }
        }

        // 5) Peremkorlát (üveg) és oszlopok fényekkel
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int d2 = dx * dx + dz * dz;
                double d = Math.sqrt(d2);
                if (d2 <= limit && d > radius - 1.5) {
                    p.set(dx, 1, dz, Material.GLASS);
                    p.set(dx, 2, dz, Material.GLASS);
                }
            }
        }
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12;
            int x = (int) Math.round((radius - 1) * Math.cos(a));
            int z = (int) Math.round((radius - 1) * Math.sin(a));
            for (int dy = 1; dy <= 5; dy++) {
                p.set(x, dy, z, Material.QUARTZ_PILLAR);
            }
            p.set(x, 6, z, Material.SEA_LANTERN);
        }

        // 6) Központi szökőkút: 3 szint
        for (int dx = -9; dx <= 9; dx++) {
            for (int dz = -9; dz <= 9; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 <= 64) {
                    if (d2 > 49) {
                        p.set(dx, 0, dz, Material.QUARTZ_BLOCK);
                        p.set(dx, 1, dz, Material.QUARTZ_BLOCK);
                    } else {
                        p.set(dx, -1, dz, Material.SEA_LANTERN);
                        p.set(dx, 0, dz, Material.WATER);
                    }
                }
                if (d2 <= 25) {
                    if (d2 > 16) {
                        p.set(dx, 1, dz, Material.SMOOTH_QUARTZ);
                        p.set(dx, 2, dz, Material.SMOOTH_QUARTZ);
                    } else {
                        p.set(dx, 1, dz, Material.SEA_LANTERN);
                        p.set(dx, 2, dz, Material.WATER);
                    }
                }
                if (d2 <= 6) {
                    if (d2 > 2) {
                        p.set(dx, 3, dz, Material.QUARTZ_BLOCK);
                        p.set(dx, 4, dz, Material.QUARTZ_BLOCK);
                    } else {
                        p.set(dx, 3, dz, Material.SEA_LANTERN);
                        p.set(dx, 4, dz, Material.WATER);
                    }
                }
            }
        }
        for (int dy = 3; dy <= 9; dy++) {
            p.set(0, dy, 0, Material.QUARTZ_PILLAR);
        }
        p.set(0, 10, 0, Material.GOLD_BLOCK);
        p.set(0, 11, 0, Material.SEA_LANTERN);

        // 7) Kapuk: négy égtáj, a peremnél (két oszlop + gerenda + fény)
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int gateD = radius - 8;
        for (int[] dir : dirs) {
            int px = dir[1] != 0 ? 1 : 0;   // oldal-irány
            int pz = dir[0] != 0 ? 1 : 0;
            for (int side : new int[]{-3, 3}) {
                for (int dy = 1; dy <= 8; dy++) {
                    p.set(dir[0] * gateD + px * side, dy, dir[1] * gateD + pz * side, Material.POLISHED_BLACKSTONE_BRICKS);
                }
                p.set(dir[0] * gateD + px * side, 9, dir[1] * gateD + pz * side, Material.GOLD_BLOCK);
            }
            for (int w = -3; w <= 3; w++) {
                p.set(dir[0] * gateD + px * w, 9, dir[1] * gateD + pz * w, Material.POLISHED_BLACKSTONE_BRICKS);
                p.set(dir[0] * gateD + px * w, 10, dir[1] * gateD + pz * w, Material.GOLD_BLOCK);
            }
            for (int w = -2; w <= 2; w++) {
                p.set(dir[0] * gateD + px * w, 8, dir[1] * gateD + pz * w, Math.abs(w) % 2 == 0 ? Material.SEA_LANTERN : Material.END_ROD);
            }
        }

        // 8) Láda-csarnok (észak): talapzatok és ládák
        int[][] spots = crateSpots(radius);
        for (int[] s : spots) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    p.set(s[0] + dx, 0, s[1] + dz, Material.GOLD_BLOCK);
                    p.set(s[0] + dx, 1, s[1] + dz, Material.POLISHED_BLACKSTONE_BRICKS);
                }
            }
            p.set(s[0], 1, s[1], Material.SMOOTH_QUARTZ);
            p.set(s[0], 2, s[1], Material.ENDER_CHEST);
            for (int[] c : new int[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}}) {
                for (int dy = 2; dy <= 5; dy++) {
                    p.set(s[0] + c[0], dy, s[1] + c[1], Material.END_ROD);
                }
            }
        }

        // 9) Fák a kertben (cseresznye és tölgy), 14 darab
        for (int i = 0; i < 14; i++) {
            double a = i * Math.PI * 2 / 14 + 0.2;
            double r = (plazaR + gardenR) / 2 + (i % 2 == 0 ? 2 : -1);
            int tx = (int) Math.round(r * Math.cos(a));
            int tz = (int) Math.round(r * Math.sin(a));
            boolean cherry = i % 2 == 0;
            Material log = cherry ? Material.CHERRY_LOG : Material.OAK_LOG;
            Material leaf = cherry ? Material.CHERRY_LEAVES : Material.OAK_LEAVES;
            if (Math.abs(tx) <= 3 || Math.abs(tz) <= 3) {
                continue; // ne zavarja az ösvényt
            }
            for (int dy = 1; dy <= 5; dy++) {
                p.set(tx, dy, tz, log);
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 4; dy <= 7; dy++) {
                        int dd = dx * dx + dz * dz + (dy - 5) * (dy - 5);
                        if (dd <= 6 && !(dx == 0 && dz == 0 && dy <= 5)) {
                            p.set(tx + dx, dy, tz + dz, leaf);
                        }
                    }
                }
            }
        }

        // 10) Lebegő szigetecskék a nagy sziget körül
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3 + 0.5;
            int ix = (int) Math.round((radius + 16) * Math.cos(a));
            int iz = (int) Math.round((radius + 16) * Math.sin(a));
            int iy = (i % 3) * 3 - 2;
            for (int k = 0; k <= 4; k++) {
                int r = Math.max(1, 5 - k);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (dx * dx + dz * dz <= r * r + r) {
                            p.set(ix + dx, iy - k, iz + dz, k == 0 ? Material.GRASS_BLOCK : (k < 2 ? Material.DIRT : Material.STONE));
                        }
                    }
                }
            }
            for (int dy = 1; dy <= 4; dy++) {
                p.set(ix, iy + dy, iz, Material.CHERRY_LOG);
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 3; dy <= 6; dy++) {
                        if (dx * dx + dz * dz + (dy - 4) * (dy - 4) <= 5 && !(dx == 0 && dz == 0 && dy <= 4)) {
                            p.set(ix + dx, iy + dy, iz + dz, Material.CHERRY_LEAVES);
                        }
                    }
                }
            }
        }

        // 11) Indító pad (keleti ösvényen): arany szegély, fekete közép, arany nyomólap
        int padX = radius / 2;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.max(Math.abs(dx), Math.abs(dz)) == 2;
                p.set(padX + dx, 0, dz, edge ? Material.GOLD_BLOCK : Material.POLISHED_BLACKSTONE);
            }
        }
        p.set(padX, 1, 0, Material.LIGHT_WEIGHTED_PRESSURE_PLATE);

        return p.ops;
    }

    /** Építés után: ládák regisztrálása, védett zóna, feliratok. */
    static void afterBuild(SMPCore plugin, World world, int cx, int cy, int cz, int radius) {
        int[][] spots = crateSpots(radius);
        for (int i = 0; i < spots.length && i < CRATE_IDS.length; i++) {
            Block chest = world.getBlockAt(cx + spots[i][0], cy + 2, cz + spots[i][1]);
            plugin.crates().registerChest(chest, CRATE_IDS[i]);
        }
        int margin = radius + 24;
        plugin.spawn().addRegion(cx - margin, cz - margin, cx + margin, cz + margin);
        text(world, cx + 0.5, cy + 14.5, cz + 0.5, "<gradient:#ffd700:#ff8c00><bold>PENTHOUSE SMP", 6f);
        text(world, cx + 0.5, cy + 12.0, cz + 0.5, "<gray>Üdv a szerveren!", 2.5f);
        text(world, cx + spots[1][0] + 0.5, cy + 6.5, cz + spots[1][1] + 0.5,
                "<gold><bold>LÁDÁK<newline><gray>Itt vehetsz kulcsot és nyithatsz", 2.5f);
        text(world, cx + radius / 2 + 0.5, cy + 4.5, cz + 0.5,
                "<gold><bold>INDULÁS A VILÁGBA<newline><gray>Állj az arany nyomólapra!", 2.5f);
    }

    private static void text(World world, double x, double y, double z, String mm, float scale) {
        world.spawn(new Location(world, x, y, z), TextDisplay.class, display -> {
            display.text(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(mm));
            display.setBillboard(Display.Billboard.CENTER);
            display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                    new Vector3f(scale, scale, scale), new AxisAngle4f()));
            display.addScoreboardTag("smpcore_lobby");
        });
    }
}

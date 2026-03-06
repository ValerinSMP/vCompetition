import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import java.util.*;

public class DumpLists {
  public static void main(String[] args) {
    List<String> ores = new ArrayList<>();
    for (Material m : Material.values()) {
      String n = m.name();
      if (n.endsWith("_ORE") || n.equals("ANCIENT_DEBRIS")) ores.add(n);
    }
    Collections.sort(ores);

    List<String> woods = new ArrayList<>();
    for (Material m : Material.values()) {
      String n = m.name();
      if (n.endsWith("_LOG") || n.endsWith("_STEM")) woods.add(n);
    }
    Collections.sort(woods);

    List<String> fish = Arrays.asList("COD", "SALMON", "PUFFERFISH", "TROPICAL_FISH");

    List<String> mobs = new ArrayList<>();
    for (EntityType e : EntityType.values()) {
      if (e == EntityType.UNKNOWN || e == EntityType.PLAYER) continue;
      if (e.isAlive() && e.isSpawnable()) mobs.add(e.name());
    }
    Collections.sort(mobs);

    System.out.println("[ORES]");
    for (String v : ores) System.out.println(v);
    System.out.println("[WOODS]");
    for (String v : woods) System.out.println(v);
    System.out.println("[FISH]");
    for (String v : fish) System.out.println(v);
    System.out.println("[MOBS]");
    for (String v : mobs) System.out.println(v);
  }
}
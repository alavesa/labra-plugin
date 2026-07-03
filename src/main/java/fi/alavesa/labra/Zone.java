package fi.alavesa.labra;

import org.bukkit.Location;

/** A spherical hazard zone. type: radiation | toxic | cryo. */
public record Zone(String name, String world, double x, double y, double z, double radius, String type) {

    public boolean contains(Location loc) {
        return loc.getWorld().getName().equals(world) && distance(loc) <= radius;
    }

    public double distance(Location loc) {
        double dx = loc.getX() - x, dy = loc.getY() - y, dz = loc.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}

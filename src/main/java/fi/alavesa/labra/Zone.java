package fi.alavesa.labra;

import org.bukkit.Location;

/** A spherical hazard zone. type: radiation | toxic | cryo | decon.
 *  alarm: when true, a siren sounds while an unprotected player is inside. */
public record Zone(String name, String world, double x, double y, double z, double radius,
                   String type, boolean alarm) {

    public boolean contains(Location loc) {
        return loc.getWorld().getName().equals(world) && distance(loc) <= radius;
    }

    public double distance(Location loc) {
        double dx = loc.getX() - x, dy = loc.getY() - y, dz = loc.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}

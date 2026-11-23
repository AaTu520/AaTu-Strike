package cn.aatu.strike;

import java.util.Optional;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

final class HeadshotGeometry {
    private static final double VANILLA_HIT_MARGIN = 0.3;
    private static final double MINIMUM_MOTION_SQUARED = 1.0E-10;

    private HeadshotGeometry() {}

    static boolean hitsHead(Projectile projectile, LivingEntity target,
                            EntityHitResult hit, double headHeightFraction) {
        AABB body = target.getBoundingBox();
        Vec3 start = projectile.position();
        Vec3 end = start.add(projectile.getDeltaMovement());
        if (!finite(start) || !finite(end) || body.getYsize() <= 0) return false;

        // Vanilla's arrow helper constructs EntityHitResult(entity), whose location is
        // the entity's feet. Reconstruct the entry point instead of using that location.
        Optional<Vec3> collision = firstContact(body, start, end);
        if (collision.isEmpty()) {
            collision = firstContact(body.inflate(VANILLA_HIT_MARGIN), start, end);
        }
        if (collision.isEmpty()) {
            Vec3 supplied = hit.getLocation();
            if (finite(supplied) && !supplied.equals(target.position())
                    && body.inflate(VANILLA_HIT_MARGIN).contains(supplied)) {
                collision = Optional.of(supplied);
            }
        }
        return collision.isPresent()
                && collision.get().y >= body.maxY - body.getYsize() * headHeightFraction;
    }

    private static Optional<Vec3> firstContact(AABB bounds, Vec3 start, Vec3 end) {
        if (bounds.contains(start)) return Optional.of(start);
        if (start.distanceToSqr(end) <= MINIMUM_MOTION_SQUARED) return Optional.empty();
        return bounds.clip(start, end);
    }

    private static boolean finite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }
}

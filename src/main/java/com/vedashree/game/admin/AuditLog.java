package com.vedashree.game.admin;

import com.vedashree.game.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Best-effort audit trail for admin security events. Never stores IP addresses or typed usernames. */
public final class AuditLog {
    private static final Logger LOG = Logger.getLogger(AuditLog.class.getName());

    private final Database database;

    /** database may be null (no persistence available): events are then only logged. */
    public AuditLog(Database database) {
        this.database = database;
    }

    public void record(String eventType, String usernameOrNull, long nowMs) {
        LOG.info("Admin audit: " + eventType);
        if (database == null) {
            return;
        }
        try (Connection c = database.openConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO admin_audit (event_type, username, occurred_ms) VALUES (?, ?, ?)")) {
            ps.setString(1, eventType);
            if (usernameOrNull == null) {
                ps.setNull(2, Types.VARCHAR);
            } else {
                ps.setString(2, usernameOrNull);
            }
            ps.setLong(3, nowMs);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Could not write admin audit entry", e);
        }
    }
}

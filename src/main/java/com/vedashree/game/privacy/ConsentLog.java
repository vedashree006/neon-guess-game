package com.vedashree.game.privacy;

import com.vedashree.game.Database;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Append-only record of consent decisions (proof of consent, Art. 7(1) GDPR). */
public final class ConsentLog {
    private final Database database;

    public ConsentLog(Database database) {
        this.database = database;
    }

    public void record(ConsentState state, long nowMs) throws SQLException {
        try (Connection connection = database.openConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO consent_log (consent_id, policy_version, analytics_allowed, "
                             + "preferences_allowed, decided_ms) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, state.consentId());
            ps.setInt(2, state.policyVersion());
            ps.setBoolean(3, state.analytics());
            ps.setBoolean(4, state.preferences());
            ps.setLong(5, nowMs);
            ps.executeUpdate();
        }
    }
}

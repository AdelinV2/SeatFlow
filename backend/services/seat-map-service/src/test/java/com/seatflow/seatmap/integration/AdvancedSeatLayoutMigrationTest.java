package com.seatflow.seatmap.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TASK-P11-001 verification: V5 advanced seat layout schema and identity-safe backfill.
 * Tests Flyway targeted at V4 -> seed -> migrate to latest, asserting exact DDL, backfill,
 * stable UUIDs, constraint enforcement and index existence via pg_indexes/pg_constraint.
 */
@Testcontainers
class AdvancedSeatLayoutMigrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("seatflow_seatmap_test")
            .withUsername("test")
            .withPassword("test");

    private static final String VENUE_1_ID = "11111111-1111-1111-1111-111111111111";
    private static final String VENUE_2_ID = "22222222-2222-2222-2222-222222222222";

    private static final String SECTION_A_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0001";
    private static final String SECTION_B_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0002";
    private static final String SECTION_C_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaa0003";
    private static final String SECTION_D_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0001";
    private static final String SECTION_E_ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbb0002";

    private static final String SEAT_1_ID = "00000000-0000-0000-0000-000000000001";
    private static final String SEAT_2_ID = "00000000-0000-0000-0000-000000000002";
    private static final String SEAT_3_ID = "00000000-0000-0000-0000-000000000003";
    private static final String SEAT_4_ID = "00000000-0000-0000-0000-000000000004";
    private static final String SEAT_5_ID = "00000000-0000-0000-0000-000000000005";
    private static final String SEAT_6_ID = "00000000-0000-0000-0000-000000000006";
    private static final String SEAT_7_ID = "00000000-0000-0000-0000-000000000007";
    private static final String SEAT_8_ID = "00000000-0000-0000-0000-000000000008";
    private static final String SEAT_9_ID = "00000000-0000-0000-0000-000000000009";
    private static final String SEAT_10_ID = "00000000-0000-0000-0000-000000000010";

    private record SeededSeat(String sectionId, int gridX, int gridY, boolean active) {
    }

    private Flyway flywayAt(String target) {
        var builder = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            builder.target(target);
        }
        return builder.load();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private List<String> querySingleColumn(String sql) throws SQLException {
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }

    private void assertConstraintViolation(String sql) throws SQLException {
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
            // if no exception, fail
            throw new AssertionError("Expected constraint violation but SQL succeeded: " + sql);
        } catch (SQLException ex) {
            String state = ex.getSQLState();
            // 23514 check, 23505 unique, 23503 fk, 22P02 invalid enum cast etc but all should be constraint-like
            // For check violations, SQLState 23514; unique 23505; fk 23503
            assertThat(state).as("SQLState for violated SQL: %s -> %s", sql, ex.getMessage())
                    .isIn("23514", "23505", "23503", "22P02", "22023");
        }
    }

    private void assertUniqueViolation(String sql) throws SQLException {
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.execute(sql);
            throw new AssertionError("Expected unique violation but SQL succeeded: " + sql);
        } catch (SQLException ex) {
            assertThat(ex.getSQLState()).as("expected unique violation for: %s msg=%s", sql, ex.getMessage())
                    .isEqualTo("23505");
        }
    }

    private void seedV4Data() throws SQLException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(true);
            // venues
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO venues (id, name, address, city, country, capacity, version, created_at, updated_at) VALUES (?::uuid, ?, ?, ?, ?, ?, 0, now(), now())")) {
                ps.setString(1, VENUE_1_ID);
                ps.setString(2, "Venue Alpha");
                ps.setString(3, "1 Alpha St");
                ps.setString(4, "NYC");
                ps.setString(5, "USA");
                ps.setInt(6, 500);
                ps.executeUpdate();

                ps.setString(1, VENUE_2_ID);
                ps.setString(2, "Venue Beta");
                ps.setString(3, "2 Beta St");
                ps.setString(4, "LA");
                ps.setString(5, "USA");
                ps.setInt(6, 600);
                ps.executeUpdate();
            }
            // venue_sections
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO venue_sections (id, venue_id, name, row_count, col_count, created_at, updated_at) VALUES (?::uuid, ?::uuid, ?, ?, ?, now(), now())")) {
                ps.setString(1, SECTION_A_ID);
                ps.setString(2, VENUE_1_ID);
                ps.setString(3, "A-Section");
                ps.setInt(4, 2);
                ps.setInt(5, 3);
                ps.executeUpdate();

                ps.setString(1, SECTION_B_ID);
                ps.setString(2, VENUE_1_ID);
                ps.setString(3, "B-Section");
                ps.setInt(4, 1);
                ps.setInt(5, 1);
                ps.executeUpdate();

                ps.setString(1, SECTION_C_ID);
                ps.setString(2, VENUE_1_ID);
                ps.setString(3, "C-Section");
                ps.setInt(4, 3);
                ps.setInt(5, 2);
                ps.executeUpdate();

                ps.setString(1, SECTION_D_ID);
                ps.setString(2, VENUE_2_ID);
                ps.setString(3, "X-Section");
                ps.setInt(4, 4);
                ps.setInt(5, 5);
                ps.executeUpdate();

                ps.setString(1, SECTION_E_ID);
                ps.setString(2, VENUE_2_ID);
                ps.setString(3, "Y-Section");
                ps.setInt(4, 1);
                ps.setInt(5, 10);
                ps.executeUpdate();
            }
            // seats
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO seats (id, section_id, row_label, seat_number, grid_x, grid_y, is_active, created_at) VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, now())")) {
                // Section A
                insertSeat(ps, SEAT_1_ID, SECTION_A_ID, "A", 1, 0, 0, true);
                insertSeat(ps, SEAT_2_ID, SECTION_A_ID, "A", 2, 1, 0, true);
                insertSeat(ps, SEAT_3_ID, SECTION_A_ID, "B", 1, 0, 1, false);
                insertSeat(ps, SEAT_4_ID, SECTION_A_ID, "B", 2, 1, 1, true);
                // Section B single row/col
                insertSeat(ps, SEAT_5_ID, SECTION_B_ID, "A", 1, 0, 0, true);
                // Section C
                insertSeat(ps, SEAT_6_ID, SECTION_C_ID, "A", 1, 2, 3, true);
                insertSeat(ps, SEAT_7_ID, SECTION_C_ID, "B", 1, 0, 0, true);
                // Section D
                insertSeat(ps, SEAT_8_ID, SECTION_D_ID, "A", 1, 5, 7, true);
                insertSeat(ps, SEAT_9_ID, SECTION_D_ID, "B", 1, 0, 0, false);
                // Section E
                insertSeat(ps, SEAT_10_ID, SECTION_E_ID, "A", 1, 1, 2, true);
            }
        }
    }

    private void insertSeat(PreparedStatement ps, String seatId, String sectionId, String rowLabel, int seatNum, int gridX, int gridY, boolean active) throws SQLException {
        ps.setString(1, seatId);
        ps.setString(2, sectionId);
        ps.setString(3, rowLabel);
        ps.setInt(4, seatNum);
        ps.setInt(5, gridX);
        ps.setInt(6, gridY);
        ps.setBoolean(7, active);
        ps.executeUpdate();
    }

    @Test
    @DisplayName("V5 migrates empty database successfully and creates expected tables/indexes")
    void shouldMigrateEmptyDatabaseSuccessfully() throws SQLException {
        Flyway fw = flywayAt(null);
        fw.clean();
        fw.migrate();

        List<String> versions = querySingleColumn("SELECT version FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(versions).containsExactly("1", "2", "3", "4", "5");

        // tables exist
        List<String> tables = querySingleColumn("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename");
        assertThat(tables).contains("venues", "venue_sections", "seats", "outbox_events", "venue_layout_elements");

        // indexes via pg_indexes for V5
        List<String> indexes = querySingleColumn("SELECT indexname FROM pg_indexes WHERE schemaname='public' AND indexname IN ('uq_seats_section_position_active','idx_venue_layout_elements_venue_id','idx_venue_layout_elements_venue_z','idx_venue_sections_venue_active') ORDER BY indexname");
        assertThat(indexes).containsExactlyInAnyOrder("uq_seats_section_position_active", "idx_venue_layout_elements_venue_id", "idx_venue_layout_elements_venue_z", "idx_venue_sections_venue_active");

        // columns not null / defaults
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT column_name, is_nullable, column_default FROM information_schema.columns WHERE table_name='venues' AND column_name='layout_version'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("is_nullable")).isEqualTo("NO");
            assertThat(rs.getString("column_default")).contains("0");
        }

        // check that empty counts are zero no exception
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM venues")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    @Test
    @DisplayName("V5 migrates venue with no sections and preserves layout_version default")
    void shouldMigrateVenueWithNoSectionsSuccessfully() throws SQLException {
        Flyway v4 = flywayAt("4");
        v4.clean();
        v4.migrate();

        String loneVenueId = "33333333-3333-3333-3333-333333333333";
        try (Connection conn = getConnection(); PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO venues (id, name, address, city, country, capacity, version, created_at, updated_at) VALUES (?::uuid, ?, ?, ?, ?, ?, 0, now(), now())")) {
            ps.setString(1, loneVenueId);
            ps.setString(2, "Lonely Venue");
            ps.setString(3, "99 Void St");
            ps.setString(4, "CHI");
            ps.setString(5, "USA");
            ps.setInt(6, 100);
            ps.executeUpdate();
        }

        Flyway latest = flywayAt(null);
        latest.migrate();

        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT layout_version FROM venues WHERE id='" + loneVenueId + "'::uuid")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isZero();
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM venue_sections WHERE venue_id='" + loneVenueId + "'::uuid")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM seats")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
            // venue_layout_elements exists even with no data
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM venue_layout_elements")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
        }
    }

    @Test
    @DisplayName("V5 backfills sections/seats deterministically, preserves UUIDs, active counts, and enforces constraints")
    void shouldBackfillSectionsAndSeatsDeterministicallyAndPreserveIdentities() throws SQLException {
        Flyway v4 = flywayAt("4");
        v4.clean();
        v4.migrate();

        seedV4Data();

        // Capture pre-V5 UUIDs and counts
        Set<String> preVenueIds = new HashSet<>(querySingleColumn("SELECT id::text FROM venues ORDER BY id"));
        Set<String> preSectionIds = new HashSet<>(querySingleColumn("SELECT id::text FROM venue_sections ORDER BY id"));
        Set<String> preSeatIds = new HashSet<>(querySingleColumn("SELECT id::text FROM seats ORDER BY id"));
        Map<String, SeededSeat> preSeatGrid = new HashMap<>();
        Map<String, Integer> preSectionRowCount = new HashMap<>();
        Map<String, Integer> preSectionColCount = new HashMap<>();
        long preActiveSeatCount;
        Map<String, Long> preActivePerVenue = new HashMap<>();
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT id::text, section_id::text, grid_x, grid_y, is_active FROM seats")) {
                while (rs.next()) {
                    preSeatGrid.put(rs.getString(1),
                            new SeededSeat(rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getBoolean(5)));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT id::text, row_count, col_count FROM venue_sections")) {
                while (rs.next()) {
                    preSectionRowCount.put(rs.getString(1), rs.getInt(2));
                    preSectionColCount.put(rs.getString(1), rs.getInt(3));
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM seats WHERE is_active = TRUE")) {
                rs.next();
                preActiveSeatCount = rs.getLong(1);
            }
            try (ResultSet rs = st.executeQuery(
                    "SELECT v.id::text, COUNT(s.id) FROM venues v LEFT JOIN venue_sections vs ON vs.venue_id=v.id LEFT JOIN seats s ON s.section_id=vs.id AND s.is_active=TRUE GROUP BY v.id")) {
                while (rs.next()) {
                    preActivePerVenue.put(rs.getString(1), rs.getLong(2));
                }
            }
        }

        // Migrate to latest (V5)
        Flyway latest = flywayAt(null);
        latest.migrate();

        List<String> versions = querySingleColumn("SELECT version FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(versions).containsExactly("1", "2", "3", "4", "5");

        // Stable ID sets: pre == post
        Set<String> postVenueIds = new HashSet<>(querySingleColumn("SELECT id::text FROM venues ORDER BY id"));
        Set<String> postSectionIds = new HashSet<>(querySingleColumn("SELECT id::text FROM venue_sections ORDER BY id"));
        Set<String> postSeatIds = new HashSet<>(querySingleColumn("SELECT id::text FROM seats ORDER BY id"));
        assertThat(postVenueIds).isEqualTo(preVenueIds);
        assertThat(postSectionIds).isEqualTo(preSectionIds);
        assertThat(postSeatIds).isEqualTo(preSeatIds);

        // No row deletion: counts unchanged
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM venues")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(preVenueIds.size());
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM venue_sections")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(preSectionIds.size());
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM seats")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(preSeatIds.size());
            }
            // active counts unchanged
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM seats WHERE is_active = TRUE")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(preActiveSeatCount);
            }
            // per venue active
            try (ResultSet rs = st.executeQuery(
                    "SELECT v.id::text, COUNT(s.id) FROM venues v LEFT JOIN venue_sections vs ON vs.venue_id=v.id LEFT JOIN seats s ON s.section_id=vs.id AND s.is_active=TRUE GROUP BY v.id")) {
                while (rs.next()) {
                    String vid = rs.getString(1);
                    long cnt = rs.getLong(2);
                    assertThat(cnt).as("active count for venue %s", vid).isEqualTo(preActivePerVenue.get(vid));
                }
            }
        }

        // layout_version = 0 for existing venues
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT id::text, layout_version FROM venues")) {
            while (rs.next()) {
                assertThat(rs.getLong("layout_version")).as("layout_version for venue %s", rs.getString(1)).isZero();
            }
        }

        // grid_x/grid_y remain NOT NULL
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name='seats' AND column_name IN ('grid_x','grid_y') ORDER BY column_name")) {
            while (rs.next()) {
                assertThat(rs.getString("is_nullable")).as("column %s should be NOT NULL", rs.getString("column_name")).isEqualTo("NO");
            }
        }

        // shape_metadata is nullable (is_nullable YES)
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT is_nullable FROM information_schema.columns WHERE table_name='venue_sections' AND column_name='shape_metadata'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("YES");
            // existing rows should have null shape_metadata
            try (ResultSet rs2 = st.executeQuery("SELECT COUNT(*) FROM venue_sections WHERE shape_metadata IS NOT NULL")) {
                rs2.next();
                assertThat(rs2.getLong(1)).isZero();
            }
        }

        // schema metadata: NOT NULL and defaults for new columns
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            // venues.layout_version
            try (ResultSet rs = st.executeQuery("SELECT is_nullable, column_default, data_type FROM information_schema.columns WHERE table_name='venues' AND column_name='layout_version'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                assertThat(rs.getString("column_default")).contains("0");
                assertThat(rs.getString("data_type")).isEqualTo("bigint");
            }
            // venue_sections.is_active default true not null
            try (ResultSet rs = st.executeQuery("SELECT is_nullable, column_default FROM information_schema.columns WHERE table_name='venue_sections' AND column_name='is_active'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                assertThat(rs.getString("column_default").toLowerCase()).contains("true");
            }
            // venue_sections.position_x etc NOT NULL
            try (ResultSet rs = st.executeQuery("SELECT column_name, is_nullable, numeric_precision, numeric_scale FROM information_schema.columns WHERE table_name='venue_sections' AND column_name IN ('position_x','position_y','width','height') ORDER BY column_name")) {
                while (rs.next()) {
                    assertThat(rs.getString("is_nullable")).as(rs.getString("column_name")).isEqualTo("NO");
                    assertThat(rs.getInt("numeric_precision")).isEqualTo(12);
                    assertThat(rs.getInt("numeric_scale")).isEqualTo(3);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT is_nullable, column_default, numeric_precision, numeric_scale FROM information_schema.columns WHERE table_name='venue_sections' AND column_name='rotation_deg'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                assertThat(rs.getString("column_default")).contains("0");
                assertThat(rs.getInt("numeric_precision")).isEqualTo(7);
                assertThat(rs.getInt("numeric_scale")).isEqualTo(3);
            }
            try (ResultSet rs = st.executeQuery("SELECT is_nullable, column_default FROM information_schema.columns WHERE table_name='venue_sections' AND column_name='z_index'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                assertThat(rs.getString("column_default")).contains("0");
            }
            // seats.position_x etc NOT NULL numeric(12,3)
            try (ResultSet rs = st.executeQuery("SELECT column_name, is_nullable, numeric_precision, numeric_scale FROM information_schema.columns WHERE table_name='seats' AND column_name IN ('position_x','position_y') ORDER BY column_name")) {
                while (rs.next()) {
                    assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                    assertThat(rs.getInt("numeric_precision")).isEqualTo(12);
                    assertThat(rs.getInt("numeric_scale")).isEqualTo(3);
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT is_nullable, column_default FROM information_schema.columns WHERE table_name='seats' AND column_name='updated_at'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("is_nullable")).isEqualTo("NO");
                assertThat(rs.getString("column_default").toLowerCase()).contains("now");
            }
            // venue_layout_elements columns
            try (ResultSet rs = st.executeQuery("SELECT column_name, is_nullable, data_type FROM information_schema.columns WHERE table_name='venue_layout_elements' ORDER BY column_name")) {
                Map<String, String> cols = new HashMap<>();
                while (rs.next()) cols.put(rs.getString("column_name"), rs.getString("is_nullable"));
                assertThat(cols).containsKeys("id", "venue_id", "type", "geometry", "z_index", "created_at", "updated_at");
                assertThat(cols.get("id")).isEqualTo("NO");
                assertThat(cols.get("venue_id")).isEqualTo("NO");
                assertThat(cols.get("type")).isEqualTo("NO");
                assertThat(cols.get("geometry")).isEqualTo("NO");
                assertThat(cols.get("z_index")).isEqualTo("NO");
            }
        }

        // check constraints existence via pg_constraint
        List<String> chkConstraints = querySingleColumn("SELECT conname FROM pg_constraint WHERE conname IN (" +
                "'chk_venues_layout_version','chk_venue_sections_position_x','chk_venue_sections_position_y'," +
                "'chk_venue_sections_width','chk_venue_sections_height','chk_venue_sections_rotation'," +
                "'chk_venue_sections_z_index','chk_venue_sections_shape_metadata'," +
                "'chk_seats_position_x','chk_seats_position_y'," +
                "'chk_venue_layout_elements_type','chk_venue_layout_elements_geometry','chk_venue_layout_elements_z_index') ORDER BY conname");
        assertThat(chkConstraints).containsExactlyInAnyOrder(
                "chk_venues_layout_version",
                "chk_venue_sections_position_x",
                "chk_venue_sections_position_y",
                "chk_venue_sections_width",
                "chk_venue_sections_height",
                "chk_venue_sections_rotation",
                "chk_venue_sections_z_index",
                "chk_venue_sections_shape_metadata",
                "chk_seats_position_x",
                "chk_seats_position_y",
                "chk_venue_layout_elements_type",
                "chk_venue_layout_elements_geometry",
                "chk_venue_layout_elements_z_index"
        );

        // indexes via pg_indexes
        List<String> expectedIndexes = querySingleColumn(
                "SELECT indexname FROM pg_indexes WHERE schemaname='public' AND indexname IN (" +
                        "'uq_seats_section_position_active','idx_venue_layout_elements_venue_id','idx_venue_layout_elements_venue_z','idx_venue_sections_venue_active') ORDER BY indexname");
        assertThat(expectedIndexes).containsExactlyInAnyOrder("uq_seats_section_position_active", "idx_venue_layout_elements_venue_id", "idx_venue_layout_elements_venue_z", "idx_venue_sections_venue_active");

        // Verify uq_seats_section_position_active is partial WHERE is_active = TRUE
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT indexdef FROM pg_indexes WHERE indexname='uq_seats_section_position_active'")) {
            assertThat(rs.next()).isTrue();
            String def = rs.getString(1).toLowerCase();
            assertThat(def).contains("is_active");
            assertThat(def).contains("true");
        }

        // Exact seat backfill: position = pre-V5 grid * 44, with per-seat identity/ownership/compatibility unchanged
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT id::text, section_id::text, grid_x, grid_y, position_x, position_y, is_active FROM seats ORDER BY id")) {
            Set<String> seenSeatIds = new HashSet<>();
            while (rs.next()) {
                String id = rs.getString(1);
                String postSectionId = rs.getString(2);
                int postGridX = rs.getInt(3);
                int postGridY = rs.getInt(4);
                BigDecimal px = rs.getBigDecimal(5);
                BigDecimal py = rs.getBigDecimal(6);
                boolean postActive = rs.getBoolean(7);
                seenSeatIds.add(id);
                SeededSeat pre = preSeatGrid.get(id);
                assertThat(pre).as("pre-V5 snapshot missing for seat %s", id).isNotNull();
                assertThat(postSectionId).as("seat %s section_id changed by V5", id).isEqualTo(pre.sectionId());
                assertThat(postGridX).as("seat %s grid_x changed by V5", id).isEqualTo(pre.gridX());
                assertThat(postGridY).as("seat %s grid_y changed by V5", id).isEqualTo(pre.gridY());
                assertThat(postActive).as("seat %s is_active changed by V5", id).isEqualTo(pre.active());
                BigDecimal expectedX = BigDecimal.valueOf(pre.gridX() * 44L).setScale(3);
                BigDecimal expectedY = BigDecimal.valueOf(pre.gridY() * 44L).setScale(3);
                assertThat(px).as("seat %s position_x", id).isEqualByComparingTo(expectedX);
                assertThat(py).as("seat %s position_y", id).isEqualByComparingTo(expectedY);
            }
            assertThat(seenSeatIds).as("post-V5 seat rows must match pre-V5 snapshot").isEqualTo(preSeatGrid.keySet());
        }

        // Verify inactive seats keep FALSE+UUID and are not changed
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT id::text, is_active FROM seats WHERE id IN ('" + SEAT_3_ID + "'::uuid, '" + SEAT_9_ID + "'::uuid) ORDER BY id")) {
            Map<String, Boolean> expectedInactive = Map.of(SEAT_3_ID, false, SEAT_9_ID, false);
            while (rs.next()) {
                String id = rs.getString(1);
                boolean active = rs.getBoolean(2);
                assertThat(active).as("seat %s is_active", id).isEqualTo(expectedInactive.get(id));
            }
        }

        // Deterministic section backfill verification
        // Compute expected per venue ordered by (name, id)
        // For venue 1: A (2x3) => 0, B (1x1) => 168, C (3x2) => 292
        // For venue 2: X (4x5) => 0, Y (1x10) => 256
        Map<String, BigDecimal[]> expectedSectionGeometry = new HashMap<>();
        expectedSectionGeometry.put(SECTION_A_ID, new BigDecimal[]{bd("0.000"), bd("0.000"), bd("132.000"), bd("88.000")});
        expectedSectionGeometry.put(SECTION_B_ID, new BigDecimal[]{bd("0.000"), bd("168.000"), bd("44.000"), bd("44.000")});
        expectedSectionGeometry.put(SECTION_C_ID, new BigDecimal[]{bd("0.000"), bd("292.000"), bd("88.000"), bd("132.000")});
        expectedSectionGeometry.put(SECTION_D_ID, new BigDecimal[]{bd("0.000"), bd("0.000"), bd("220.000"), bd("176.000")});
        expectedSectionGeometry.put(SECTION_E_ID, new BigDecimal[]{bd("0.000"), bd("256.000"), bd("440.000"), bd("44.000")});

        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT id::text, position_x, position_y, width, height, is_active, rotation_deg, z_index FROM venue_sections ORDER BY name, id")) {
            while (rs.next()) {
                String id = rs.getString(1);
                BigDecimal px = rs.getBigDecimal(2);
                BigDecimal py = rs.getBigDecimal(3);
                BigDecimal w = rs.getBigDecimal(4);
                BigDecimal h = rs.getBigDecimal(5);
                boolean active = rs.getBoolean(6);
                BigDecimal rot = rs.getBigDecimal(7);
                int z = rs.getInt(8);
                BigDecimal[] exp = expectedSectionGeometry.get(id);
                assertThat(exp).as("expected geometry missing for section %s", id).isNotNull();
                assertThat(px).as("section %s position_x", id).isEqualByComparingTo(exp[0]);
                assertThat(py).as("section %s position_y", id).isEqualByComparingTo(exp[1]);
                assertThat(w).as("section %s width", id).isEqualByComparingTo(exp[2]);
                assertThat(h).as("section %s height", id).isEqualByComparingTo(exp[3]);
                assertThat(active).as("section %s is_active should be TRUE after backfill", id).isTrue();
                assertThat(rot).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(z).isZero();
            }
        }

        // Single-row / single-col sections 44.000 already asserted via SECTION_B
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT width, height FROM venue_sections WHERE id='" + SECTION_B_ID + "'::uuid")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(bd("44.000"));
            assertThat(rs.getBigDecimal(2)).isEqualByComparingTo(bd("44.000"));
        }

        // Partial unique index behavior
        // Active-active duplicate should be rejected
        String duplicateActiveSql = "INSERT INTO seats (id, section_id, row_label, seat_number, grid_x, grid_y, is_active, created_at, position_x, position_y, updated_at) VALUES (gen_random_uuid(), '" + SECTION_A_ID + "'::uuid, 'Z99', 999, 99, 99, TRUE, now(), 0, 0, now())";
        assertUniqueViolation(duplicateActiveSql);

        // Inactive collision with active should be allowed
        String inactiveCollisionSql = "INSERT INTO seats (id, section_id, row_label, seat_number, grid_x, grid_y, is_active, created_at, position_x, position_y, updated_at) VALUES ('99999999-9999-9999-9999-999999999999'::uuid, '" + SECTION_A_ID + "'::uuid, 'Z98', 998, 98, 98, FALSE, now(), 0, 0, now())";
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(inactiveCollisionSql);
            // verify inserted
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM seats WHERE id='99999999-9999-9999-9999-999999999999'::uuid")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(1L);
            }
            // cleanup
            st.executeUpdate("DELETE FROM seats WHERE id='99999999-9999-9999-9999-999999999999'::uuid");
        }

        // Multiple inactive at same position should be allowed (second inactive same position as previous inactive)
        String inactive1 = "INSERT INTO seats (id, section_id, row_label, seat_number, grid_x, grid_y, is_active, created_at, position_x, position_y, updated_at) VALUES ('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeee1'::uuid, '" + SECTION_A_ID + "'::uuid, 'Z97', 997, 97, 97, FALSE, now(), 88, 88, now())";
        String inactive2 = "INSERT INTO seats (id, section_id, row_label, seat_number, grid_x, grid_y, is_active, created_at, position_x, position_y, updated_at) VALUES ('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeee2'::uuid, '" + SECTION_A_ID + "'::uuid, 'Z96', 996, 96, 96, FALSE, now(), 88, 88, now())";
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate(inactive1);
            st.executeUpdate(inactive2);
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM seats WHERE section_id='" + SECTION_A_ID + "'::uuid AND position_x=88 AND position_y=88 AND is_active=FALSE")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(2L);
            }
            st.executeUpdate("DELETE FROM seats WHERE id IN ('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeee1'::uuid,'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeee2'::uuid)");
        }

        // Constraint violations - all should be rejected
        // venues.layout_version negative
        assertConstraintViolation("UPDATE venues SET layout_version=-1 WHERE id='" + VENUE_1_ID + "'::uuid");
        // venue_sections position_x negative / over 100000
        assertConstraintViolation("UPDATE venue_sections SET position_x=-1 WHERE id='" + SECTION_A_ID + "'::uuid");
        assertConstraintViolation("UPDATE venue_sections SET position_x=100001 WHERE id='" + SECTION_A_ID + "'::uuid");
        assertConstraintViolation("UPDATE venue_sections SET position_y=-0.001 WHERE id='" + SECTION_A_ID + "'::uuid");
        assertConstraintViolation("UPDATE venue_sections SET position_y=100001 WHERE id='" + SECTION_A_ID + "'::uuid");
        // width zero / negative / over 100000
        assertConstraintViolation("UPDATE venue_sections SET width=0 WHERE id='" + SECTION_A_ID + "'::uuid");
        assertConstraintViolation("UPDATE venue_sections SET width=-10 WHERE id='" + SECTION_A_ID + "'::uuid");
        assertConstraintViolation("UPDATE venue_sections SET width=100001 WHERE id='" + SECTION_A_ID + "'::uuid");
        assertConstraintViolation("UPDATE venue_sections SET height=0 WHERE id='" + SECTION_A_ID + "'::uuid");
        assertConstraintViolation("UPDATE venue_sections SET height=100001 WHERE id='" + SECTION_A_ID + "'::uuid");
        // rotation out of range
        assertConstraintViolation("UPDATE venue_sections SET rotation_deg=181 WHERE id='" + SECTION_A_ID + "'::uuid");
        assertConstraintViolation("UPDATE venue_sections SET rotation_deg=-181 WHERE id='" + SECTION_A_ID + "'::uuid");
        // z_index out of range
        assertConstraintViolation("UPDATE venue_sections SET z_index=1001 WHERE id='" + SECTION_A_ID + "'::uuid");
        assertConstraintViolation("UPDATE venue_sections SET z_index=-1001 WHERE id='" + SECTION_A_ID + "'::uuid");
        // shape_metadata non-object: array
        assertConstraintViolation("UPDATE venue_sections SET shape_metadata='[]'::jsonb WHERE id='" + SECTION_A_ID + "'::uuid");
        // shape_metadata non-object: string literal json
        assertConstraintViolation("UPDATE venue_sections SET shape_metadata='\"hello\"'::jsonb WHERE id='" + SECTION_A_ID + "'::uuid");
        // shape_metadata non-object: number
        assertConstraintViolation("UPDATE venue_sections SET shape_metadata='123'::jsonb WHERE id='" + SECTION_A_ID + "'::uuid");
        // valid shape_metadata object should succeed
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("UPDATE venue_sections SET shape_metadata='{\"type\":\"rect\"}'::jsonb WHERE id='" + SECTION_A_ID + "'::uuid");
            try (ResultSet rs = st.executeQuery("SELECT shape_metadata FROM venue_sections WHERE id='" + SECTION_A_ID + "'::uuid")) {
                rs.next();
                assertThat(rs.getString(1)).contains("rect");
            }
            st.executeUpdate("UPDATE venue_sections SET shape_metadata=NULL WHERE id='" + SECTION_A_ID + "'::uuid");
        }

        // seats position_x / y negative / over 100000
        assertConstraintViolation("UPDATE seats SET position_x=-1 WHERE id='" + SEAT_1_ID + "'::uuid");
        assertConstraintViolation("UPDATE seats SET position_x=100001 WHERE id='" + SEAT_1_ID + "'::uuid");
        assertConstraintViolation("UPDATE seats SET position_y=-1 WHERE id='" + SEAT_1_ID + "'::uuid");
        assertConstraintViolation("UPDATE seats SET position_y=100001 WHERE id='" + SEAT_1_ID + "'::uuid");

        // venue_layout_elements: invalid type
        assertConstraintViolation("INSERT INTO venue_layout_elements (id, venue_id, type, geometry) VALUES (gen_random_uuid(), '" + VENUE_1_ID + "'::uuid, 'INVALID_TYPE', '{\"x\":1}'::jsonb)");
        // venue_layout_elements: non-object geometry (array)
        assertConstraintViolation("INSERT INTO venue_layout_elements (venue_id, type, geometry) VALUES ('" + VENUE_1_ID + "'::uuid, 'STAGE', '[]'::jsonb)");
        assertConstraintViolation("INSERT INTO venue_layout_elements (venue_id, type, geometry) VALUES ('" + VENUE_1_ID + "'::uuid, 'STAGE', '\"string\"'::jsonb)");
        assertConstraintViolation("INSERT INTO venue_layout_elements (venue_id, type, geometry) VALUES ('" + VENUE_1_ID + "'::uuid, 'STAGE', '123'::jsonb)");
        // z_index out of range for layout elements
        assertConstraintViolation("INSERT INTO venue_layout_elements (venue_id, type, geometry, z_index) VALUES ('" + VENUE_1_ID + "'::uuid, 'STAGE', '{\"x\":1}'::jsonb, 1001)");
        assertConstraintViolation("INSERT INTO venue_layout_elements (venue_id, type, geometry, z_index) VALUES ('" + VENUE_1_ID + "'::uuid, 'STAGE', '{\"x\":1}'::jsonb, -1001)");

        // valid layout elements should succeed for all five types
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            String[] types = {"STAGE", "AISLE", "LABEL", "BARRIER", "DECORATION"};
            for (String t : types) {
                st.executeUpdate("INSERT INTO venue_layout_elements (venue_id, type, geometry) VALUES ('" + VENUE_1_ID + "'::uuid, '" + t + "', '{\"x\":1,\"y\":2}'::jsonb)");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM venue_layout_elements WHERE venue_id='" + VENUE_1_ID + "'::uuid")) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(5L);
            }
            // check defaults for z_index and timestamps
            try (ResultSet rs = st.executeQuery("SELECT z_index, created_at, updated_at FROM venue_layout_elements WHERE type='STAGE' LIMIT 1")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
                assertThat(rs.getTimestamp(2)).isNotNull();
                assertThat(rs.getTimestamp(3)).isNotNull();
            }
            // verify index usage not required, but venue_id and venue_z indexes exist (already checked)
            // test FK cascade not needed but verify FK exists
            List<String> fk = querySingleColumn("SELECT conname FROM pg_constraint WHERE conname='fk_venue_layout_elements_venues'");
            assertThat(fk).contains("fk_venue_layout_elements_venues");
        }

        // Ensure updated_at for seats is NOT NULL and has now() default
        try (Connection conn = getConnection(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT updated_at FROM seats WHERE id='" + SEAT_1_ID + "'::uuid")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getTimestamp(1)).isNotNull();
        }

        // Insert new venue without specifying layout_version should default to 0
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            String newVenueId = "44444444-4444-4444-4444-444444444444";
            st.executeUpdate("INSERT INTO venues (id, name, address, city, country, capacity, version, created_at, updated_at) VALUES ('" + newVenueId + "'::uuid, 'New Venue', 'Addr', 'CITY', 'USA', 100, 0, now(), now())");
            try (ResultSet rs = st.executeQuery("SELECT layout_version FROM venues WHERE id='" + newVenueId + "'::uuid")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
            st.executeUpdate("DELETE FROM venues WHERE id='" + newVenueId + "'::uuid");
        }

        // Insert new section without specifying optional fields should get defaults 0 and TRUE
        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            String tmpSectionId = "cccccccc-cccc-cccc-cccc-cccccccc0001";
            st.executeUpdate("INSERT INTO venue_sections (id, venue_id, name, row_count, col_count, position_x, position_y, width, height, created_at, updated_at) VALUES ('" + tmpSectionId + "'::uuid, '" + VENUE_1_ID + "'::uuid, 'TempSection', 2, 2, 0, 500, 88, 88, now(), now())");
            try (ResultSet rs = st.executeQuery("SELECT is_active, rotation_deg, z_index, shape_metadata FROM venue_sections WHERE id='" + tmpSectionId + "'::uuid")) {
                rs.next();
                assertThat(rs.getBoolean(1)).isTrue();
                assertThat(rs.getBigDecimal(2)).isEqualByComparingTo(BigDecimal.ZERO);
                assertThat(rs.getInt(3)).isZero();
                assertThat(rs.getString(4)).isNull();
            }
            st.executeUpdate("DELETE FROM venue_sections WHERE id='" + tmpSectionId + "'::uuid");
        }
    }

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}

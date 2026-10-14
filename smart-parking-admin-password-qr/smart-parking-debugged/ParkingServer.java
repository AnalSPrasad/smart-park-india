import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Smart Parking Management: a dependency-free Java web app.
 *
 * Run:   java ParkingServer.java        (Java 17+)
 * Open:  http://localhost:8081
 *
 * The server exposes a small JSON API under /api and serves the web UI from ./public.
 * All data lives in memory and resets when the server restarts.
 */
public class ParkingServer {

    // ------------------------------------------------------------------ pricing rules
    static final int FREE_MINUTES = 15;          // grace period at the barrier
    static final double DAILY_CAP = 20.00;       // max charge per 24 h
    static final double BUSY_THRESHOLD = 0.80;   // occupancy that triggers busy pricing
    static final double BUSY_MULTIPLIER = 1.25;  // locked in at check-in

    // ------------------------------------------------------------------ domain
    enum SlotType { STANDARD, EV, ACCESSIBLE, MOTORCYCLE }

    /** A vehicle type, its hourly rate, and the bay types it may use (most preferred first). */
    enum VehicleType {
        CAR(3.00, List.of(SlotType.STANDARD)),
        EV(3.50, List.of(SlotType.EV, SlotType.STANDARD)),
        MOTORCYCLE(1.50, List.of(SlotType.MOTORCYCLE, SlotType.STANDARD)),
        ACCESSIBLE(2.00, List.of(SlotType.ACCESSIBLE, SlotType.STANDARD));

        final double hourlyRate;
        final List<SlotType> preference;

        VehicleType(double hourlyRate, List<SlotType> preference) {
            this.hourlyRate = hourlyRate;
            this.preference = preference;
        }
    }

    static class ApiException extends RuntimeException {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }

    static class Slot {
        final String floor;
        final int number;
        final String id;
        final SlotType type;
        String plate;
        VehicleType vehicle;
        Instant entry;
        double multiplier = 1.0;
        String reservedFor;
        Instant reservedUntil;

        Slot(String floor, int number, SlotType type) {
            this.floor = floor;
            this.number = number;
            this.type = type;
            this.id = floor + "-" + String.format("%02d", number);
        }

        boolean occupied() { return plate != null; }
        boolean reserved() { return reservedFor != null; }
        boolean free() { return !occupied() && !reserved(); }
        String status() { return occupied() ? "occupied" : reserved() ? "reserved" : "free"; }
    }

    record Receipt(String ticket, String plate, String slotId, VehicleType vehicle,
                   Instant entry, Instant exit, long minutes, double multiplier, double fee) { }

    // ------------------------------------------------------------------ the garage
    static class Lot {
        final List<Slot> slots = new ArrayList<>();
        final List<Receipt> history = new ArrayList<>();
        int ticketCounter = 1000;

        Lot() {
            for (String floor : List.of("A", "B")) {
                for (int n = 1; n <= 20; n++) slots.add(new Slot(floor, n, layout(n)));
            }
            seed();
        }

        /** Bays 1-2 accessible, 3-5 EV charging, 6-7 motorcycle, the rest standard. */
        static SlotType layout(int n) {
            if (n <= 2) return SlotType.ACCESSIBLE;
            if (n <= 5) return SlotType.EV;
            if (n <= 7) return SlotType.MOTORCYCLE;
            return SlotType.STANDARD;
        }

        static VehicleType vehicleFor(SlotType t) {
            return switch (t) {
                case EV -> VehicleType.EV;
                case ACCESSIBLE -> VehicleType.ACCESSIBLE;
                case MOTORCYCLE -> VehicleType.MOTORCYCLE;
                default -> VehicleType.CAR;
            };
        }

        /** Fill the garage with believable demo traffic. */
        private void seed() {
            Random rnd = new Random(7);
            Instant now = Instant.now();
            List<Slot> pool = new ArrayList<>(slots);
            Collections.shuffle(pool, rnd);

            for (Slot s : pool.subList(0, 17)) {
                s.plate = randomPlate(rnd);
                s.vehicle = vehicleFor(s.type);
                s.entry = now.minus(Duration.ofMinutes(5 + rnd.nextInt(300)));
            }
            for (Slot s : pool.subList(17, 20)) {
                if (s.type == SlotType.STANDARD) {
                    s.reservedFor = randomPlate(rnd);
                    s.reservedUntil = now.plus(Duration.ofMinutes(20 + rnd.nextInt(80)));
                }
            }
            long sinceMidnight = Duration.between(
                    LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant(), now).toMinutes();
            int bound = (int) Math.max(2, Math.min(sinceMidnight, 400));
            for (int i = 0; i < 7; i++) {
                VehicleType v = VehicleType.values()[rnd.nextInt(4)];
                long stay = 10 + rnd.nextInt(200);
                Instant exit = now.minus(Duration.ofMinutes(1 + rnd.nextInt(bound)));
                history.add(new Receipt("T-" + (++ticketCounter), randomPlate(rnd),
                        "A-" + String.format("%02d", 8 + rnd.nextInt(12)), v,
                        exit.minus(Duration.ofMinutes(stay)), exit, stay, 1.0, fee(v, stay, 1.0)));
            }
        }

        private static String randomPlate(Random rnd) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 3; i++) sb.append((char) ('A' + rnd.nextInt(26)));
            return sb + "-" + (100 + rnd.nextInt(900));
        }

        // ---- pricing
        static double fee(VehicleType v, long minutes, double multiplier) {
            if (minutes <= FREE_MINUTES) return 0;
            long hours = (minutes + 59) / 60;
            long days = (minutes + 1439) / 1440;
            double raw = hours * v.hourlyRate * multiplier;
            return Math.round(Math.min(raw, DAILY_CAP * days) * 100.0) / 100.0;
        }

        static long minutesSince(Instant entry, Instant now) {
            return (Duration.between(entry, now).getSeconds() + 59) / 60;
        }

        synchronized double currentMultiplier() {
            long busy = slots.stream().filter(s -> !s.free()).count();
            return (double) busy / slots.size() >= BUSY_THRESHOLD ? BUSY_MULTIPLIER : 1.0;
        }

        // ---- lookups
        private Slot byId(String id) {
            return slots.stream().filter(s -> s.id.equalsIgnoreCase(id)).findFirst().orElse(null);
        }

        private Slot byPlate(String plate) {
            return slots.stream().filter(s -> plate.equals(s.plate)).findFirst().orElse(null);
        }

        private void expireReservations() {
            Instant now = Instant.now();
            for (Slot s : slots) {
                if (s.reserved() && s.reservedUntil.isBefore(now)) {
                    s.reservedFor = null;
                    s.reservedUntil = null;
                }
            }
        }

        // ---- actions
        synchronized Slot checkIn(String plate, VehicleType v, String wantedId) {
            expireReservations();
            Slot already = byPlate(plate);
            if (already != null) throw new ApiException(409, plate + " is already parked in bay " + already.id + ".");

            Slot target = slots.stream().filter(s -> plate.equals(s.reservedFor)).findFirst().orElse(null);
            if (target == null && wantedId != null && !wantedId.isBlank()) {
                target = byId(wantedId);
                if (target == null) throw new ApiException(404, "Bay " + wantedId + " does not exist.");
                if (!target.free()) throw new ApiException(409, "Bay " + target.id + " is not available.");
                if (!v.preference.contains(target.type)) {
                    throw new ApiException(422, "Bay " + target.id + " (" + target.type.name().toLowerCase()
                            + ") isn't suitable for this vehicle type.");
                }
            } else if (target == null) {
                for (SlotType t : v.preference) {
                    target = slots.stream().filter(s -> s.free() && s.type == t).findFirst().orElse(null);
                    if (target != null) break;
                }
                if (target == null) throw new ApiException(409, "No free bay for this vehicle type right now.");
            }

            double multiplier = currentMultiplier();
            target.reservedFor = null;
            target.reservedUntil = null;
            target.plate = plate;
            target.vehicle = v;
            target.entry = Instant.now();
            target.multiplier = multiplier;
            return target;
        }

        synchronized Receipt checkOut(String plate) {
            Slot s = byPlate(plate);
            if (s == null) throw new ApiException(404, "No parked vehicle with plate " + plate + ".");
            Instant now = Instant.now();
            long minutes = minutesSince(s.entry, now);
            Receipt r = new Receipt("T-" + (++ticketCounter), plate, s.id, s.vehicle, s.entry, now,
                    minutes, s.multiplier, fee(s.vehicle, minutes, s.multiplier));
            history.add(r);
            s.plate = null;
            s.vehicle = null;
            s.entry = null;
            s.multiplier = 1.0;
            return r;
        }

        synchronized Slot reserve(String slotId, String plate, VehicleType v, int minutes) {
            expireReservations();
            Slot s = byId(slotId);
            if (s == null) throw new ApiException(404, "Bay " + slotId + " does not exist.");
            if (!s.free()) throw new ApiException(409, "Bay " + s.id + " is not available.");
            if (!v.preference.contains(s.type)) {
                throw new ApiException(422, "Bay " + s.id + " (" + s.type.name().toLowerCase()
                        + ") isn't suitable for this vehicle type.");
            }
            if (byPlate(plate) != null) throw new ApiException(409, plate + " is already parked.");
            if (slots.stream().anyMatch(x -> plate.equals(x.reservedFor))) {
                throw new ApiException(409, plate + " already has a reservation.");
            }
            if (minutes < 5 || minutes > 240) throw new ApiException(422, "Reservations run from 5 to 240 minutes.");
            s.reservedFor = plate;
            s.reservedUntil = Instant.now().plus(Duration.ofMinutes(minutes));
            return s;
        }

        synchronized Slot cancelReservation(String slotId) {
            Slot s = byId(slotId);
            if (s == null) throw new ApiException(404, "Bay " + slotId + " does not exist.");
            if (!s.reserved()) throw new ApiException(409, "Bay " + s.id + " has no reservation.");
            s.reservedFor = null;
            s.reservedUntil = null;
            return s;
        }

        // ---- views
        synchronized List<Map<String, Object>> slotsView() {
            expireReservations();
            Instant now = Instant.now();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Slot s : slots) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.id);
                m.put("floor", s.floor);
                m.put("number", s.number);
                m.put("type", s.type);
                m.put("status", s.status());
                if (s.occupied()) {
                    long mins = minutesSince(s.entry, now);
                    m.put("plate", s.plate);
                    m.put("vehicle", s.vehicle);
                    m.put("since", s.entry);
                    m.put("minutes", mins);
                    m.put("estimatedFee", fee(s.vehicle, mins, s.multiplier));
                }
                if (s.reserved()) {
                    m.put("reservedFor", s.reservedFor);
                    m.put("reservedUntil", s.reservedUntil);
                }
                out.add(m);
            }
            return out;
        }

        synchronized Map<String, Object> statsView() {
            expireReservations();
            int total = slots.size();
            long occupied = slots.stream().filter(Slot::occupied).count();
            long reserved = slots.stream().filter(Slot::reserved).count();
            Map<String, Object> byType = new LinkedHashMap<>();
            for (SlotType t : SlotType.values()) {
                byType.put(t.name(), Map.of(
                        "total", slots.stream().filter(s -> s.type == t).count(),
                        "free", slots.stream().filter(s -> s.type == t && s.free()).count()));
            }
            LocalDate today = LocalDate.now();
            ZoneId zone = ZoneId.systemDefault();
            List<Receipt> todays = history.stream()
                    .filter(r -> r.exit().atZone(zone).toLocalDate().equals(today)).toList();
            double revenue = todays.stream().mapToDouble(Receipt::fee).sum();
            double avgStay = todays.stream().mapToLong(Receipt::minutes).average().orElse(0);

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("total", total);
            m.put("occupied", occupied);
            m.put("reserved", reserved);
            m.put("free", total - occupied - reserved);
            m.put("occupancyPercent", Math.round(100.0 * (occupied + reserved) / total));
            m.put("byType", byType);
            m.put("revenueToday", Math.round(revenue * 100.0) / 100.0);
            m.put("vehiclesServedToday", todays.size());
            m.put("averageStayMinutes", Math.round(avgStay));
            m.put("multiplier", currentMultiplier());
            m.put("freeMinutes", FREE_MINUTES);
            m.put("dailyCap", DAILY_CAP);
            return m;
        }

        synchronized List<Map<String, Object>> historyView(int limit) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (int i = history.size() - 1; i >= 0 && out.size() < limit; i--) out.add(receiptView(history.get(i)));
            return out;
        }
    }

    static Map<String, Object> receiptView(Receipt r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticket", r.ticket());
        m.put("plate", r.plate());
        m.put("slotId", r.slotId());
        m.put("vehicle", r.vehicle());
        m.put("hourlyRate", r.vehicle().hourlyRate);
        m.put("entry", r.entry());
        m.put("exit", r.exit());
        m.put("minutes", r.minutes());
        m.put("multiplier", r.multiplier());
        m.put("fee", r.fee());
        return m;
    }

    // ------------------------------------------------------------------ HTTP layer
    static final Lot lot = new Lot();

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0])
                : Integer.parseInt(System.getenv().getOrDefault("PORT", "8081"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/", ParkingServer::handleApi);
        server.createContext("/", ParkingServer::handleStatic);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Smart Parking is running at http://localhost:" + port);
    }

    static void handleApi(HttpExchange ex) throws IOException {
        try {
            String route = ex.getRequestMethod() + " " + ex.getRequestURI().getPath();
            Object result = switch (route) {
                case "GET /api/state" -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("serverTime", Instant.now());
                    m.put("slots", lot.slotsView());
                    m.put("stats", lot.statsView());
                    m.put("history", lot.historyView(8));
                    yield m;
                }
                case "GET /api/slots" -> lot.slotsView();
                case "GET /api/stats" -> lot.statsView();
                case "GET /api/history" -> lot.historyView(50);
                case "POST /api/checkin" -> {
                    Map<String, String> b = readBody(ex);
                    Slot s = lot.checkIn(plate(b), vehicle(b), b.get("slotId"));
                    yield Map.of("slotId", s.id, "plate", s.plate, "multiplier", s.multiplier);
                }
                case "POST /api/checkout" -> receiptView(lot.checkOut(plate(readBody(ex))));
                case "POST /api/reserve" -> {
                    Map<String, String> b = readBody(ex);
                    int minutes;
                    try { minutes = Integer.parseInt(b.getOrDefault("minutes", "30")); }
                    catch (NumberFormatException e) { throw new ApiException(422, "Minutes must be a number."); }
                    Slot s = lot.reserve(b.getOrDefault("slotId", ""), plate(b), vehicle(b), minutes);
                    yield Map.of("slotId", s.id, "plate", s.reservedFor);
                }
                case "POST /api/cancel" -> {
                    Slot s = lot.cancelReservation(readBody(ex).getOrDefault("slotId", ""));
                    yield Map.of("slotId", s.id);
                }
                default -> throw new ApiException(404, "Unknown endpoint: " + route);
            };
            send(ex, 200, "application/json", json(result));
        } catch (ApiException e) {
            send(ex, e.status, "application/json", json(Map.of("error", e.getMessage())));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, "application/json", json(Map.of("error", "Something went wrong on the server.")));
        }
    }

    static String plate(Map<String, String> body) {
        String p = body.getOrDefault("plate", "").trim().toUpperCase();
        if (!p.matches("[A-Z0-9][A-Z0-9 -]{1,9}")) {
            throw new ApiException(422, "Enter a plate of 2 to 10 letters, numbers, spaces or dashes.");
        }
        return p;
    }

    static VehicleType vehicle(Map<String, String> body) {
        try { return VehicleType.valueOf(body.getOrDefault("vehicle", "CAR").toUpperCase()); }
        catch (IllegalArgumentException e) { throw new ApiException(422, "Unknown vehicle type."); }
    }

    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        // Resolve public/ from the project directory, not from an arbitrary
        // working directory selected by a VS Code extension.
        Path root = Path.of("public").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            try {
                Path codeLocation = Path.of(ParkingServer.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
                Path candidate = Files.isDirectory(codeLocation) ? codeLocation.resolve("public")
                        : codeLocation.getParent().resolve("public");
                if (Files.isDirectory(candidate)) root = candidate;
            } catch (Exception ignored) { }
        }
        Path file = root.resolve(path.substring(1)).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found");
            return;
        }
        String name = file.getFileName().toString();
        String type = name.endsWith(".html") ? "text/html; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : name.endsWith(".js") ? "text/javascript; charset=utf-8"
                : name.endsWith(".svg") ? "image/svg+xml"
                : name.endsWith(".png") ? "image/png"
                : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg"
                : name.endsWith(".webp") ? "image/webp" : "application/octet-stream";
        send(ex, 200, type, Files.readAllBytes(file));
    }

    static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        send(ex, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType.startsWith("application/json")
                ? "application/json; charset=utf-8" : contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (var out = ex.getResponseBody()) { out.write(body); }
    }

    // ------------------------------------------------------------------ tiny JSON helpers
    private static final Pattern FIELD =
            Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^,}\\s]+))");

    /** Reads a flat JSON object (string and number values) into a map. */
    static Map<String, String> readBody(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> out = new HashMap<>();
        Matcher m = FIELD.matcher(body);
        while (m.find()) {
            out.put(m.group(1), m.group(2) != null
                    ? m.group(2).replace("\\\"", "\"").replace("\\\\", "\\") : m.group(3));
        }
        return out;
    }

    static String json(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String s) quote(sb, s);
        else if (o instanceof Enum<?> e) quote(sb, e.name());
        else if (o instanceof Instant i) sb.append(i.toEpochMilli());
        else if (o instanceof Number || o instanceof Boolean) sb.append(o);
        else if (o instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object item : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item);
            }
            sb.append(']');
        } else quote(sb, o.toString());
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        sb.append('"');
    }
}
